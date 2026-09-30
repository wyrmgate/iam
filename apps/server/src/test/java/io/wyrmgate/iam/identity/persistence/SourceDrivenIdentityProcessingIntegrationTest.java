package io.wyrmgate.iam.identity.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.identity.application.CanonicalAttributeConfigurationService;
import io.wyrmgate.iam.identity.application.CanonicalAttributeResolutionService;
import io.wyrmgate.iam.identity.application.IdentityCommandService;
import io.wyrmgate.iam.identity.application.SourceCorrelationPolicyService;
import io.wyrmgate.iam.identity.application.SourceCorrelationService;
import io.wyrmgate.iam.identity.application.SourceDrivenIdentityProcessingService;
import io.wyrmgate.iam.identity.application.SourceMappedValueExtractor;
import io.wyrmgate.iam.identity.application.SourceLifecyclePolicyService;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeCardinality;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeState;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeType;
import io.wyrmgate.iam.identity.domain.CanonicalValue;
import io.wyrmgate.iam.identity.domain.Identity;
import io.wyrmgate.iam.identity.domain.IdentityLifecycleState;
import io.wyrmgate.iam.identity.domain.IdentityProfile;
import io.wyrmgate.iam.identity.domain.IdentityType;
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

class SourceDrivenIdentityProcessingIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static JdbcOutboxRepository outbox;
    private static JdbcIdentityRepository identityRepository;
    private static JdbcSourceCorrelationRepository sourceRepository;
    private static JdbcCanonicalAttributeRepository attributeRepository;
    private static IdentityCommandService identities;
    private static SourceCorrelationService sources;
    private static CanonicalAttributeConfigurationService configuration;
    private static CanonicalAttributeResolutionService resolution;
    private static SourceCorrelationPolicyService policies;
    private static SourceLifecyclePolicyService lifecyclePolicies;
    private static SourceDrivenIdentityProcessingService processor;
    private static TransactionExecutor transactions;

    @BeforeAll
    static void startPostgresAndMigrate() {
        POSTGRES.start();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(dataSource).load();
        flyway.migrate();
        flyway.validate();

        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new JdbcTenantRepository(jdbc, ids);
        outbox = new JdbcOutboxRepository(jdbc);
        identityRepository = new JdbcIdentityRepository(jdbc);
        sourceRepository = new JdbcSourceCorrelationRepository(jdbc, ids);
        attributeRepository = new JdbcCanonicalAttributeRepository(jdbc, ids);
        transactions = new SpringTransactionExecutor(new DataSourceTransactionManager(dataSource));

        identities = new IdentityCommandService(
                identityRepository,
                new JdbcIdentityFactSink(outbox, ids),
                ids,
                transactions);
        sources = new SourceCorrelationService(
                sourceRepository,
                identityRepository,
                new JdbcSourceCorrelationFactSink(outbox, ids),
                ids,
                transactions);
        configuration = new CanonicalAttributeConfigurationService(
                attributeRepository,
                sourceRepository,
                new JdbcCanonicalAttributeFactSink(outbox, ids),
                ids,
                transactions);
        resolution = new CanonicalAttributeResolutionService(
                attributeRepository,
                sourceRepository,
                identityRepository,
                new JdbcCanonicalAttributeFactSink(outbox, ids),
                ids,
                transactions);
        policies = new SourceCorrelationPolicyService(
                sourceRepository,
                attributeRepository,
                ids,
                transactions);
        ObjectMapper json = new ObjectMapper();
        SourceMappedValueExtractor extractor = new SourceMappedValueExtractor(json);
        lifecyclePolicies = new SourceLifecyclePolicyService(
                sourceRepository, identityRepository, identities, extractor, ids, transactions);
        processor = new SourceDrivenIdentityProcessingService(
                outbox,
                sourceRepository,
                attributeRepository,
                sources,
                identities,
                resolution,
                lifecyclePolicies,
                extractor,
                transactions,
                json);

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("43");
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @BeforeEach
    void clearRows() {
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
    void uniqueGovernedMatchCorrelatesAndResolvesAffectedMoverAttributes() {
        Instant now = Instant.now();
        TenantContext tenant = tenant("Unique Match", now);
        SourceSystem authoritative = source(tenant, "hr", now);
        SourceSystem incoming = source(tenant, "directory", now.plusSeconds(1));
        activateSchema(tenant, now.plusSeconds(2));
        mapAndAuthorize(tenant, authoritative, "employeeId", "$.employeeId", 1, now.plusSeconds(3));
        mapAndAuthorize(tenant, incoming, "employeeId", "$.employeeId", 10, now.plusSeconds(3));
        mapAndAuthorize(tenant, incoming, "department", "$.department", 10, now.plusSeconds(3));

        Identity canonical = identity(tenant, "Ada", IdentityLifecycleState.ACTIVE, now.plusSeconds(4));
        SourceRecord seed = observe(
                tenant, authoritative, "seed-1",
                "{\"employeeId\":\"E-100\"}", now.plusSeconds(5));
        sources.acceptCorrelation(
                tenant, seed.id(), canonical.id(), "seed governed identity",
                now.plusSeconds(6), ids.nextId(), null);
        resolution.recordCandidate(
                tenant, seed.id(), "employeeId",
                List.of(new CanonicalValue.StringValue("E-100")), now.plusSeconds(7));
        resolution.resolve(
                tenant, canonical.id(), "employeeId",
                now.plusSeconds(8), ids.nextId(), null);

        policies.activate(
                tenant, incoming.id(), "employeeId",
                false, null, null, now.plusSeconds(9));
        SourceRecord record = observe(
                tenant, incoming, "directory-100",
                "{\"employeeId\":\"E-100\",\"department\":\"ENGINEERING\"}",
                now.plusSeconds(10));

        drain();

        assertThat(sourceRepository.findActiveAcceptedLink(tenant, record.id()))
                .get()
                .extracting(link -> link.identityId())
                .isEqualTo(canonical.id());
        assertThat(canonicalString(tenant, canonical.id(), "department"))
                .contains("ENGINEERING");
    }

    @Test
    void ambiguousCanonicalMatchNeverGuessesOrCreates() {
        Instant now = Instant.now();
        TenantContext tenant = tenant("Ambiguous Match", now);
        SourceSystem seedSource = source(tenant, "hr", now);
        SourceSystem incoming = source(tenant, "directory", now.plusSeconds(1));
        activateSchema(tenant, now.plusSeconds(2));
        mapAndAuthorize(tenant, seedSource, "employeeId", "$.employeeId", 1, now.plusSeconds(3));
        mapAndAuthorize(tenant, incoming, "employeeId", "$.employeeId", 10, now.plusSeconds(3));

        seedResolvedEmployeeId(
                tenant, seedSource, identity(tenant, "First", IdentityLifecycleState.ACTIVE, now.plusSeconds(4)),
                "seed-a", "DUPLICATE", now.plusSeconds(5));
        seedResolvedEmployeeId(
                tenant, seedSource, identity(tenant, "Second", IdentityLifecycleState.ACTIVE, now.plusSeconds(6)),
                "seed-b", "DUPLICATE", now.plusSeconds(7));

        policies.activate(
                tenant, incoming.id(), "employeeId",
                true, IdentityType.PERSON, "$.displayName", now.plusSeconds(8));
        SourceRecord record = observe(
                tenant, incoming, "ambiguous",
                "{\"employeeId\":\"DUPLICATE\",\"displayName\":\"Should Not Exist\"}",
                now.plusSeconds(9));

        drain();

        assertThat(sourceRepository.findActiveAcceptedLink(tenant, record.id())).isEmpty();
        assertThat(identityCount(tenant)).isEqualTo(2);
    }

    @Test
    void noMatchCreationRequiresPolicyAndStartsPending() {
        Instant now = Instant.now();
        TenantContext tenant = tenant("Joiner", now);
        SourceSystem incoming = source(tenant, "hr", now);
        activateSchema(tenant, now.plusSeconds(1));
        mapAndAuthorize(tenant, incoming, "employeeId", "$.employeeId", 1, now.plusSeconds(2));
        policies.activate(
                tenant, incoming.id(), "employeeId",
                true, IdentityType.PERSON, "$.displayName", now.plusSeconds(3));

        SourceRecord record = observe(
                tenant, incoming, "employee-200",
                "{\"employeeId\":\"E-200\",\"displayName\":\"New Joiner\"}",
                now.plusSeconds(4));

        drain();

        var link = sourceRepository.findActiveAcceptedLink(tenant, record.id()).orElseThrow();
        Identity created = identityRepository.findById(tenant, link.identityId()).orElseThrow();
        assertThat(created.lifecycleState()).isEqualTo(IdentityLifecycleState.PENDING);
        assertThat(created.displayName()).isEqualTo("New Joiner");
        assertThat(canonicalString(tenant, created.id(), "employeeId"))
                .contains("E-200");
    }

    @Test
    void sourceCreatedPendingIdentityAdvancesToActiveThroughProcessorPolicy() {
        Instant now = Instant.now();
        TenantContext tenant = tenant("Lifecycle Joiner Processor", now);
        SourceSystem incoming = source(tenant, "hr", now);
        activateSchema(tenant, now.plusSeconds(1));
        mapAndAuthorize(tenant, incoming, "employeeId", "$.employeeId", 1, now.plusSeconds(2));
        policies.activate(
                tenant, incoming.id(), "employeeId",
                true, IdentityType.PERSON, "$.displayName", now.plusSeconds(3));
        lifecyclePolicies.activate(
                tenant,
                incoming.id(),
                "$.employmentStatus",
                java.util.Map.of("ACTIVE", IdentityLifecycleState.ACTIVE),
                now.plusSeconds(4));

        SourceRecord record = observe(
                tenant,
                incoming,
                "employee-joiner-active",
                "{\"employeeId\":\"E-201\",\"displayName\":\"Active Joiner\",\"employmentStatus\":\"ACTIVE\"}",
                now.plusSeconds(5));

        drain();

        var link = sourceRepository.findActiveAcceptedLink(tenant, record.id()).orElseThrow();
        Identity created = identityRepository.findById(tenant, link.identityId()).orElseThrow();
        assertThat(created.lifecycleState()).isEqualTo(IdentityLifecycleState.ACTIVE);
    }

    @Test
    void acceptedLinkIsStickyEvenWhenCurrentCorrelationKeyPointsElsewhere() {
        Instant now = Instant.now();
        TenantContext tenant = tenant("Sticky Link", now);
        SourceSystem seedSource = source(tenant, "hr", now);
        SourceSystem incoming = source(tenant, "directory", now.plusSeconds(1));
        activateSchema(tenant, now.plusSeconds(2));
        mapAndAuthorize(tenant, seedSource, "employeeId", "$.employeeId", 1, now.plusSeconds(3));
        mapAndAuthorize(tenant, incoming, "employeeId", "$.employeeId", 10, now.plusSeconds(3));

        Identity sticky = identity(tenant, "Sticky", IdentityLifecycleState.ACTIVE, now.plusSeconds(4));
        Identity other = identity(tenant, "Other", IdentityLifecycleState.ACTIVE, now.plusSeconds(5));
        seedResolvedEmployeeId(
                tenant, seedSource, other, "seed-other", "MATCH-OTHER", now.plusSeconds(6));

        policies.activate(
                tenant, incoming.id(), "employeeId",
                false, null, null, now.plusSeconds(7));
        SourceRecord record = observe(
                tenant, incoming, "sticky-record",
                "{\"employeeId\":\"MATCH-OTHER\"}", now.plusSeconds(8));
        sources.acceptCorrelation(
                tenant, record.id(), sticky.id(), "explicit operator correlation",
                now.plusSeconds(9), ids.nextId(), null);

        drain();

        assertThat(sourceRepository.findActiveAcceptedLink(tenant, record.id()))
                .get()
                .extracting(link -> link.identityId())
                .isEqualTo(sticky.id());
    }

    @Test
    void linkReplacementStopsOldCandidateAuthorityAndReresolvesBothSides() {
        Instant now = Instant.now();
        TenantContext tenant = tenant("Relink", now);
        SourceSystem source = source(tenant, "hr", now);
        activateSchema(tenant, now.plusSeconds(1));
        mapAndAuthorize(tenant, source, "employeeId", "$.employeeId", 1, now.plusSeconds(2));

        Identity first = identity(tenant, "First", IdentityLifecycleState.ACTIVE, now.plusSeconds(3));
        Identity second = identity(tenant, "Second", IdentityLifecycleState.ACTIVE, now.plusSeconds(4));
        SourceRecord record = observe(
                tenant, source, "employee-300",
                "{\"employeeId\":\"E-300\"}", now.plusSeconds(5));
        sources.acceptCorrelation(
                tenant, record.id(), first.id(), "initial link",
                now.plusSeconds(6), ids.nextId(), null);
        resolution.recordCandidate(
                tenant, record.id(), "employeeId",
                List.of(new CanonicalValue.StringValue("E-300")), now.plusSeconds(7));
        CanonicalAttributeState initial = resolution.resolve(
                tenant, first.id(), "employeeId",
                now.plusSeconds(8), ids.nextId(), null);
        assertThat(initial.resolutionStatus())
                .isEqualTo(CanonicalAttributeState.ResolutionStatus.RESOLVED);

        sources.acceptCorrelation(
                tenant, record.id(), second.id(), "explicit corrected link",
                now.plusSeconds(9), ids.nextId(), null);

        UUID employeeIdVersion = jdbc.queryForObject(
                """
                SELECT version.id
                FROM identity.attribute_definition_version version
                JOIN identity.attribute_definition definition
                  ON definition.tenant_id = version.tenant_id
                 AND definition.id = version.attribute_definition_id
                JOIN identity.canonical_schema_version schema
                  ON schema.tenant_id = version.tenant_id
                 AND schema.id = version.schema_version_id
                WHERE version.tenant_id = ?
                  AND definition.canonical_key = 'employeeId'
                  AND schema.state = 'ACTIVE'
                """,
                UUID.class,
                tenant.tenantId());
        assertThat(attributeRepository.findCandidates(
                tenant, first.id(), employeeIdVersion)).isEmpty();

        drain();

        assertThat(canonicalStatus(tenant, first.id(), "employeeId"))
                .contains("NO_VALUE");
        assertThat(canonicalString(tenant, second.id(), "employeeId"))
                .contains("E-300");
    }

    @Test
    void staleObservationFactRereadsLatestRecordAndNeverCreatesFromOldValue() {
        Instant now = Instant.now();
        TenantContext tenant = tenant("Replay", now);
        SourceSystem source = source(tenant, "hr", now);
        activateSchema(tenant, now.plusSeconds(1));
        mapAndAuthorize(tenant, source, "employeeId", "$.employeeId", 1, now.plusSeconds(2));
        policies.activate(
                tenant, source.id(), "employeeId",
                true, IdentityType.PERSON, "$.displayName", now.plusSeconds(3));

        observe(
                tenant, source, "employee-400",
                "{\"employeeId\":\"OLD\",\"displayName\":\"Old Name\"}",
                now.plusSeconds(4));
        SourceRecord latest = observe(
                tenant, source, "employee-400",
                "{\"employeeId\":\"NEW\",\"displayName\":\"Current Name\"}",
                now.plusSeconds(5));

        drain();

        var link = sourceRepository.findActiveAcceptedLink(tenant, latest.id()).orElseThrow();
        Identity created = identityRepository.findById(tenant, link.identityId()).orElseThrow();
        assertThat(created.displayName()).isEqualTo("Current Name");
        assertThat(canonicalString(tenant, created.id(), "employeeId")).contains("NEW");
        assertThat(identityCount(tenant)).isEqualTo(1);
    }

    private static void drain() {
        for (int i = 0; i < 4; i++) {
            var result = processor.processAvailable();
            assertThat(result.failed())
                    .describedAs("source processor failures: %s",
                            jdbc.queryForList(
                                    """
                                    SELECT event_type, publication_state, last_error_code
                                    FROM platform.outbox_event
                                    WHERE event_type IN (
                                        'identity.source-record-observed',
                                        'identity.identity-link-accepted')
                                      AND (publication_state = 'FAILED'
                                           OR last_error_code IS NOT NULL)
                                    ORDER BY occurred_at, id
                                    """))
                    .isZero();
            if (result.claimed() == 0) {
                return;
            }
        }
    }

    private static TenantContext tenant(String displayName, Instant now) {
        return new TenantContext(tenants.create(displayName, now).id());
    }

    private static SourceSystem source(TenantContext tenant, String code, Instant now) {
        return sources.createSourceSystem(
                tenant, code, code, now, ids.nextId(), null);
    }

    private static Identity identity(
            TenantContext tenant,
            String displayName,
            IdentityLifecycleState state,
            Instant now) {
        return identities.create(
                tenant,
                IdentityType.PERSON,
                new IdentityProfile.PersonProfile(),
                state,
                displayName,
                now,
                ids.nextId(),
                null);
    }

    private static SourceRecord observe(
            TenantContext tenant,
            SourceSystem source,
            String nativeKey,
            String json,
            Instant now) {
        var run = sources.startImport(tenant, source.id(), now);
        return sources.observe(
                tenant,
                run.id(),
                nativeKey,
                json,
                now,
                now,
                ids.nextId(),
                null);
    }

    private static void activateSchema(TenantContext tenant, Instant now) {
        var schema = configuration.createDraftSchema(tenant, 1, now);
        configuration.defineAttribute(
                tenant,
                schema.id(),
                "employeeId",
                CanonicalAttributeType.STRING,
                CanonicalAttributeCardinality.SINGLE,
                "INTERNAL",
                true,
                true,
                true,
                now.plusMillis(1));
        configuration.defineAttribute(
                tenant,
                schema.id(),
                "department",
                CanonicalAttributeType.STRING,
                CanonicalAttributeCardinality.SINGLE,
                "INTERNAL",
                true,
                true,
                true,
                now.plusMillis(2));
        configuration.activateSchema(
                tenant, schema.id(), now.plusMillis(3), ids.nextId(), null);
    }

    private static void mapAndAuthorize(
            TenantContext tenant,
            SourceSystem source,
            String key,
            String path,
            int priority,
            Instant now) {
        configuration.activateMapping(tenant, source.id(), key, path, now);
        configuration.activateAuthority(
                tenant, source.id(), key, priority, now.plusMillis(1));
    }

    private static void seedResolvedEmployeeId(
            TenantContext tenant,
            SourceSystem source,
            Identity identity,
            String nativeKey,
            String employeeId,
            Instant now) {
        SourceRecord record = observe(
                tenant,
                source,
                nativeKey,
                "{\"employeeId\":\"" + employeeId + "\"}",
                now);
        sources.acceptCorrelation(
                tenant,
                record.id(),
                identity.id(),
                "seed identity",
                now.plusMillis(1),
                ids.nextId(),
                null);
        resolution.recordCandidate(
                tenant,
                record.id(),
                "employeeId",
                List.of(new CanonicalValue.StringValue(employeeId)),
                now.plusMillis(2));
        resolution.resolve(
                tenant,
                identity.id(),
                "employeeId",
                now.plusMillis(3),
                ids.nextId(),
                null);
    }

    private static java.util.Optional<String> canonicalString(
            TenantContext tenant, UUID identityId, String key) {
        List<String> rows = jdbc.query(
                """
                SELECT value.value_string
                FROM identity.canonical_attribute_state state
                JOIN identity.attribute_definition definition
                  ON definition.tenant_id = state.tenant_id
                 AND definition.id = state.attribute_definition_id
                JOIN identity.canonical_attribute_state_value value
                  ON value.tenant_id = state.tenant_id
                 AND value.state_id = state.id
                WHERE state.tenant_id = ?
                  AND state.identity_id = ?
                  AND definition.canonical_key = ?
                  AND value.value_ordinal = 0
                """,
                (rs, rowNum) -> rs.getString("value_string"),
                tenant.tenantId(),
                identityId,
                key);
        return rows.stream().findFirst();
    }

    private static java.util.Optional<String> canonicalStatus(
            TenantContext tenant, UUID identityId, String key) {
        List<String> rows = jdbc.query(
                """
                SELECT state.resolution_status
                FROM identity.canonical_attribute_state state
                JOIN identity.attribute_definition definition
                  ON definition.tenant_id = state.tenant_id
                 AND definition.id = state.attribute_definition_id
                WHERE state.tenant_id = ?
                  AND state.identity_id = ?
                  AND definition.canonical_key = ?
                """,
                (rs, rowNum) -> rs.getString("resolution_status"),
                tenant.tenantId(),
                identityId,
                key);
        return rows.stream().findFirst();
    }

    private static int identityCount(TenantContext tenant) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM identity.identity WHERE tenant_id = ?",
                Integer.class,
                tenant.tenantId());
        return count == null ? 0 : count;
    }
}
