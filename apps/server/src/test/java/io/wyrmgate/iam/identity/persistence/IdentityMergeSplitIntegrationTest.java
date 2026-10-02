package io.wyrmgate.iam.identity.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.wyrmgate.iam.identity.application.IdentityCommandService;
import io.wyrmgate.iam.identity.application.IdentityMergeSplitService;
import io.wyrmgate.iam.identity.application.SourceCorrelationService;
import io.wyrmgate.iam.identity.domain.Identity;
import io.wyrmgate.iam.identity.domain.IdentityLifecycleState;
import io.wyrmgate.iam.identity.domain.IdentityProfile;
import io.wyrmgate.iam.identity.domain.IdentityType;
import io.wyrmgate.iam.identity.domain.Principal;
import io.wyrmgate.iam.identity.domain.PrincipalKind;
import io.wyrmgate.iam.identity.domain.PrincipalLifecycleState;
import io.wyrmgate.iam.identity.domain.SourceImportCompleteness;
import io.wyrmgate.iam.identity.domain.SourceImportRun;
import io.wyrmgate.iam.identity.domain.SourceRecord;
import io.wyrmgate.iam.identity.domain.SourceSystem;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.JdbcTenantRepository;
import io.wyrmgate.iam.platform.persistence.SpringTransactionExecutor;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class IdentityMergeSplitIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");
    private static final Instant NOW = Instant.parse("2026-09-30T08:00:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static JdbcOutboxRepository outbox;
    private static JdbcIdentityRepository identityRepository;
    private static JdbcPrincipalRepository principalRepository;
    private static JdbcSourceCorrelationRepository sourceRepository;
    private static IdentityCommandService identities;
    private static SourceCorrelationService sources;
    private static IdentityMergeSplitService mergeSplit;
    private static TransactionExecutor transactions;

    @BeforeAll
    static void start() {
        POSTGRES.start();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(dataSource).load();
        flyway.migrate();
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("51");

        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new JdbcTenantRepository(jdbc, ids);
        outbox = new JdbcOutboxRepository(jdbc);
        transactions = new SpringTransactionExecutor(new DataSourceTransactionManager(dataSource));
        identityRepository = new JdbcIdentityRepository(jdbc);
        principalRepository = new JdbcPrincipalRepository(jdbc);
        sourceRepository = new JdbcSourceCorrelationRepository(jdbc, ids);

        var identityFacts = new JdbcIdentityFactSink(outbox, ids);
        var principalFacts = new JdbcPrincipalFactSink(outbox, ids);
        var sourceFacts = new JdbcSourceCorrelationFactSink(outbox, ids);
        identities = new IdentityCommandService(
                identityRepository, identityFacts, ids, transactions);
        sources = new SourceCorrelationService(
                sourceRepository, identityRepository, sourceFacts, ids, transactions);
        mergeSplit = new IdentityMergeSplitService(
                new JdbcIdentityMergeSplitRepository(jdbc, identityRepository),
                identityRepository,
                sourceRepository,
                sourceFacts,
                principalFacts,
                identities,
                ids,
                transactions);
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @BeforeEach
    void clear() {
        jdbc.execute("""
                TRUNCATE TABLE
                    platform.scheduled_work,
                    platform.idempotency_record,
                    platform.inbox_message,
                    platform.outbox_event,
                    platform.tenant
                CASCADE
                """);
    }

    @Test
    void mergeMovesIdentityOwnedRelationshipsAndDecommissionsAbsorbed() {
        TenantContext tenant = tenant("merge");
        Identity survivor = identity(tenant, IdentityType.PERSON, "Survivor", NOW);
        Identity absorbed = identity(tenant, IdentityType.PERSON, "Absorbed", NOW.plusSeconds(1));
        SourceSystem source = source(tenant, "hr", NOW.plusSeconds(2));
        SourceRecord record = linkedRecord(
                tenant, source, absorbed, "employee-1", NOW.plusSeconds(3));
        Principal principal = principal(
                tenant, absorbed.id(), "absorbed-account", NOW.plusSeconds(4));

        var operation = mergeSplit.merge(
                tenant,
                survivor.id(),
                survivor.revision(),
                absorbed.id(),
                absorbed.revision(),
                "duplicate person confirmed",
                NOW.plusSeconds(5),
                ids.nextId(),
                null);

        var currentLink = sourceRepository.findActiveAcceptedLink(tenant, record.id())
                .orElseThrow();
        Principal moved = principalRepository.findById(tenant, principal.id()).orElseThrow();
        Identity terminal = identityRepository.findById(tenant, absorbed.id()).orElseThrow();

        assertThat(currentLink.identityId()).isEqualTo(survivor.id());
        assertThat(moved.identityId()).isEqualTo(survivor.id());
        assertThat(moved.revision()).isEqualTo(principal.revision() + 1);
        assertThat(terminal.lifecycleState()).isEqualTo(IdentityLifecycleState.DECOMMISSIONED);
        assertThat(operation.movedLinkCount()).isEqualTo(1);
        assertThat(operation.movedPrincipalCount()).isEqualTo(1);

        Integer historicalLinks = jdbc.queryForObject(
                """
                SELECT count(*) FROM identity.identity_link
                WHERE tenant_id = ? AND source_record_id = ?
                """,
                Integer.class,
                tenant.tenantId(),
                record.id());
        assertThat(historicalLinks).isEqualTo(2);

        String oldState = jdbc.queryForObject(
                """
                SELECT link_state FROM identity.identity_link
                WHERE tenant_id = ? AND source_record_id = ? AND identity_id = ?
                """,
                String.class,
                tenant.tenantId(),
                record.id(),
                absorbed.id());
        assertThat(oldState).isEqualTo("SUPERSEDED");

        Integer eligibilityFacts = jdbc.queryForObject(
                """
                SELECT count(*) FROM platform.outbox_event
                WHERE tenant_id = ? AND aggregate_id = ?
                  AND event_type = 'identity.access-eligibility-changed'
                """,
                Integer.class,
                tenant.tenantId(),
                absorbed.id());
        assertThat(eligibilityFacts).isEqualTo(1);

        String reassignmentPayload = jdbc.queryForObject(
                """
                SELECT payload::text FROM platform.outbox_event
                WHERE tenant_id = ? AND aggregate_id = ?
                  AND event_type = 'identity.principal-access-projection-input-changed'
                ORDER BY occurred_at DESC, id DESC
                LIMIT 1
                """,
                String.class,
                tenant.tenantId(),
                principal.id());
        assertThat(reassignmentPayload).contains(absorbed.id().toString());
    }

    @Test
    void splitCreatesPendingIdentityAndMovesOnlySelectedRelationships() {
        TenantContext tenant = tenant("split");
        Identity sourceIdentity = identity(
                tenant, IdentityType.PERSON, "Source", NOW);
        SourceSystem source = source(tenant, "hr", NOW.plusSeconds(1));
        SourceRecord moveRecord = linkedRecord(
                tenant, source, sourceIdentity, "move", NOW.plusSeconds(2));
        SourceRecord keepRecord = linkedRecord(
                tenant, source, sourceIdentity, "keep", NOW.plusSeconds(3));
        Principal movePrincipal = principal(
                tenant, sourceIdentity.id(), "move-account", NOW.plusSeconds(4));
        Principal keepPrincipal = principal(
                tenant, sourceIdentity.id(), "keep-account", NOW.plusSeconds(5));

        var result = mergeSplit.split(
                tenant,
                sourceIdentity.id(),
                sourceIdentity.revision(),
                "Separated Person",
                List.of(moveRecord.id()),
                List.of(movePrincipal.id()),
                "records belong to two people",
                NOW.plusSeconds(6),
                ids.nextId(),
                null);

        Identity created = result.newIdentity();
        assertThat(created.type()).isEqualTo(sourceIdentity.type());
        assertThat(created.lifecycleState()).isEqualTo(IdentityLifecycleState.PENDING);

        assertThat(sourceRepository.findActiveAcceptedLink(tenant, moveRecord.id())
                .orElseThrow().identityId()).isEqualTo(created.id());
        assertThat(sourceRepository.findActiveAcceptedLink(tenant, keepRecord.id())
                .orElseThrow().identityId()).isEqualTo(sourceIdentity.id());

        assertThat(principalRepository.findById(tenant, movePrincipal.id())
                .orElseThrow().identityId()).isEqualTo(created.id());
        assertThat(principalRepository.findById(tenant, keepPrincipal.id())
                .orElseThrow().identityId()).isEqualTo(sourceIdentity.id());

        assertThat(result.operation().movedSourceRecordIds())
                .containsExactly(moveRecord.id());
        assertThat(result.operation().movedPrincipalIds())
                .containsExactly(movePrincipal.id());
        assertThat(identityRepository.findById(tenant, sourceIdentity.id())
                .orElseThrow().lifecycleState()).isEqualTo(IdentityLifecycleState.ACTIVE);
    }

    @Test
    void splitInvalidSelectionFailsAtomicallyWithoutCreatingIdentity() {
        TenantContext tenant = tenant("atomic");
        Identity source = identity(tenant, IdentityType.PERSON, "Source", NOW);
        Identity other = identity(tenant, IdentityType.PERSON, "Other", NOW.plusSeconds(1));
        Principal foreignPrincipal = principal(
                tenant, other.id(), "other-account", NOW.plusSeconds(2));

        Integer before = countIdentities(tenant);

        assertThatThrownBy(() -> mergeSplit.split(
                tenant,
                source.id(),
                source.revision(),
                "Should Not Exist",
                List.of(),
                List.of(foreignPrincipal.id()),
                "invalid selection",
                NOW.plusSeconds(3),
                ids.nextId(),
                null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(countIdentities(tenant)).isEqualTo(before);
        assertThat(principalRepository.findById(tenant, foreignPrincipal.id())
                .orElseThrow().identityId()).isEqualTo(other.id());
    }

    @Test
    void mergeRejectsDifferentIdentityTypesWithoutMutation() {
        TenantContext tenant = tenant("type");
        Identity person = identity(tenant, IdentityType.PERSON, "Person", NOW);
        Identity service = identity(tenant, IdentityType.SERVICE, "Service", NOW.plusSeconds(1));

        assertThatThrownBy(() -> mergeSplit.merge(
                tenant,
                person.id(),
                person.revision(),
                service.id(),
                service.revision(),
                "invalid cross type",
                NOW.plusSeconds(2),
                ids.nextId(),
                null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(identityRepository.findById(tenant, service.id())
                .orElseThrow().lifecycleState()).isEqualTo(IdentityLifecycleState.ACTIVE);
    }

    private static TenantContext tenant(String name) {
        return new TenantContext(tenants.create(name, NOW).id());
    }

    private static Identity identity(
            TenantContext tenant,
            IdentityType type,
            String displayName,
            Instant at) {
        return identities.create(
                tenant,
                type,
                switch (type) {
                    case PERSON -> new IdentityProfile.PersonProfile();
                    case SERVICE -> new IdentityProfile.ServiceProfile();
                    case WORKLOAD -> new IdentityProfile.WorkloadProfile();
                },
                IdentityLifecycleState.ACTIVE,
                displayName,
                at,
                ids.nextId(),
                null);
    }

    private static SourceSystem source(
            TenantContext tenant,
            String code,
            Instant at) {
        return sources.createSourceSystem(
                tenant, code, code, at, ids.nextId(), null);
    }

    private static SourceRecord linkedRecord(
            TenantContext tenant,
            SourceSystem source,
            Identity identity,
            String nativeKey,
            Instant at) {
        SourceImportRun run = sources.startImport(tenant, source.id(), at);
        SourceRecord record = sources.observe(
                tenant,
                run.id(),
                nativeKey,
                "{\"name\":\"" + nativeKey + "\"}",
                null,
                at.plusMillis(1),
                ids.nextId(),
                null);
        sources.acceptCorrelation(
                tenant,
                record.id(),
                identity.id(),
                "test link",
                at.plusMillis(2),
                ids.nextId(),
                null);
        sources.completeImport(
                tenant,
                run.id(),
                SourceImportCompleteness.COMPLETE,
                null,
                null,
                at.plusMillis(3),
                ids.nextId(),
                null);
        return record;
    }

    private static Principal principal(
            TenantContext tenant,
            UUID identityId,
            String nativeKey,
            Instant at) {
        Principal principal = new Principal(
                ids.nextId(),
                identityId,
                ids.nextId(),
                PrincipalKind.ACCOUNT,
                nativeKey,
                PrincipalLifecycleState.ACTIVE,
                1,
                at,
                at);
        principalRepository.insert(tenant, principal);
        return principal;
    }

    private static Integer countIdentities(TenantContext tenant) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM identity.identity WHERE tenant_id = ?",
                Integer.class,
                tenant.tenantId());
    }
}
