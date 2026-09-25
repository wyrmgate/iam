package io.wyrmgate.iam.identity.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.wyrmgate.iam.catalog.application.CatalogQueryService;
import io.wyrmgate.iam.catalog.persistence.JdbcCatalogRepository;
import io.wyrmgate.iam.identity.application.IdentityCommandService;
import io.wyrmgate.iam.identity.application.PrincipalCommandException;
import io.wyrmgate.iam.identity.application.PrincipalCommandService;
import io.wyrmgate.iam.identity.application.PrincipalQueryService;
import io.wyrmgate.iam.identity.application.PrincipalResolutionQuery;
import io.wyrmgate.iam.identity.domain.Identity;
import io.wyrmgate.iam.identity.domain.IdentityLifecycleState;
import io.wyrmgate.iam.identity.domain.IdentityProfile;
import io.wyrmgate.iam.identity.domain.IdentityType;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.JdbcTenantRepository;
import io.wyrmgate.iam.platform.persistence.SpringTransactionExecutor;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
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

class PrincipalPersistenceIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");
    private static final Instant NOW = Instant.parse("2026-09-25T04:30:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static JdbcOutboxRepository outbox;
    private static TransactionExecutor transactions;
    private static IdentityCommandService identities;
    private static PrincipalCommandService principals;
    private static PrincipalQueryService principalQuery;

    @BeforeAll
    static void start() {
        POSTGRES.start();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(dataSource).load();
        flyway.migrate();
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("19");

        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new JdbcTenantRepository(jdbc, ids);
        outbox = new JdbcOutboxRepository(jdbc);
        transactions = new SpringTransactionExecutor(
                new DataSourceTransactionManager(dataSource));

        var identityRepository = new JdbcIdentityRepository(jdbc);
        identities = new IdentityCommandService(
                identityRepository,
                new JdbcIdentityFactSink(outbox, ids),
                ids,
                transactions);

        var principalRepository = new JdbcPrincipalRepository(jdbc);
        principalQuery = new PrincipalQueryService(principalRepository);
        principals = new PrincipalCommandService(
                principalRepository,
                identityRepository,
                new CatalogQueryService(new JdbcCatalogRepository(jdbc)),
                new JdbcPrincipalFactSink(outbox, ids),
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
                    identity.principal,
                    identity.person_profile,
                    identity.service_profile,
                    identity.workload_profile,
                    identity.identity,
                    catalog.entitlement,
                    catalog.application_target,
                    catalog.application,
                    platform.outbox_event,
                    platform.tenant
                CASCADE
                """);
    }

    @Test
    void createsUnmatchedPrincipalThenCorrelatesExactlyOnce() {
        TenantContext tenant = tenant("principal");
        UUID target = target(tenant, "app", "prod");

        var principal = principals.create(
                tenant, target, "provider-user-123", null,
                NOW, ids.nextId(), null);

        assertThat(principal.identityId()).isNull();
        assertThat(principal.revision()).isEqualTo(1);
        assertThat(principalQuery.resolve(tenant, target, "provider-user-123"))
                .isEqualTo(PrincipalResolutionQuery.Resolution.uncorrelated(principal.id()));

        Identity identity = identity(tenant, "Ada");
        var correlated = principals.correlate(
                tenant, principal.id(), identity.id(), principal.revision(),
                NOW.plusSeconds(1), ids.nextId(), null);

        assertThat(correlated.identityId()).isEqualTo(identity.id());
        assertThat(correlated.revision()).isEqualTo(2);
        assertThat(principalQuery.resolve(tenant, target, "provider-user-123"))
                .isEqualTo(PrincipalResolutionQuery.Resolution.resolved(
                        principal.id(), identity.id()));

        String payload = jdbc.queryForObject("""
                SELECT payload::text
                FROM platform.outbox_event
                WHERE tenant_id = ?
                  AND event_type = 'identity.principal-correlated'
                  AND aggregate_id = ?
                """, String.class, tenant.tenantId(), principal.id());
        assertThat(payload)
                .contains(target.toString())
                .doesNotContain("provider-user-123")
                .doesNotContain(identity.id().toString());

        assertThatThrownBy(() -> principals.correlate(
                tenant, principal.id(), identity.id(), correlated.revision(),
                NOW.plusSeconds(2), ids.nextId(), null))
                .isInstanceOf(PrincipalCommandException.class)
                .hasMessageContaining("already correlated");
    }

    @Test
    void rejectsDuplicateNativePrincipalCrossTenantIdentityRetiredTargetAndStaleWrite() {
        TenantContext tenant = tenant("owner");
        TenantContext other = tenant("other");
        UUID target = target(tenant, "app", "prod");
        Identity ownerIdentity = identity(tenant, "Owner");
        Identity otherIdentity = identity(other, "Other");

        var principal = principals.create(
                tenant, target, "native-1", null,
                NOW, ids.nextId(), null);

        assertThatThrownBy(() -> principals.create(
                tenant, target, "native-1", null,
                NOW.plusSeconds(1), ids.nextId(), null))
                .isInstanceOf(PrincipalCommandException.class)
                .hasMessageContaining("already exists");

        assertThatThrownBy(() -> principals.correlate(
                tenant, principal.id(), otherIdentity.id(), principal.revision(),
                NOW.plusSeconds(2), ids.nextId(), null))
                .isInstanceOf(PrincipalCommandException.class)
                .hasMessageContaining("Identity was not found");

        var correlated = principals.correlate(
                tenant, principal.id(), ownerIdentity.id(), principal.revision(),
                NOW.plusSeconds(3), ids.nextId(), null);

        assertThatThrownBy(() -> principals.correlate(
                tenant, principal.id(), ownerIdentity.id(), principal.revision(),
                NOW.plusSeconds(4), ids.nextId(), null))
                .isInstanceOf(StaleWriteException.class);

        jdbc.update("""
                UPDATE catalog.application_target
                SET lifecycle_state = 'RETIRED', revision = revision + 1, updated_at = ?
                WHERE tenant_id = ? AND id = ?
                """,
                Timestamp.from(NOW.plusSeconds(5)), tenant.tenantId(), target);

        assertThatThrownBy(() -> principals.create(
                tenant, target, "native-2", null,
                NOW.plusSeconds(6), ids.nextId(), null))
                .isInstanceOf(PrincipalCommandException.class)
                .hasMessageContaining("retired");

        assertThat(principalQuery.resolve(other, target, "native-1").status())
                .isEqualTo(PrincipalResolutionQuery.Status.NOT_FOUND);
        assertThat(correlated.identityId()).isEqualTo(ownerIdentity.id());
    }

    private TenantContext tenant(String name) {
        return new TenantContext(tenants.create(name, NOW).id());
    }

    private Identity identity(TenantContext tenant, String displayName) {
        return identities.create(
                tenant,
                IdentityType.PERSON,
                new IdentityProfile.PersonProfile(),
                IdentityLifecycleState.ACTIVE,
                displayName,
                NOW,
                ids.nextId(),
                null);
    }

    private UUID target(TenantContext tenant, String applicationCode, String targetCode) {
        UUID applicationId = ids.nextId();
        UUID targetId = ids.nextId();
        jdbc.update("""
                INSERT INTO catalog.application (
                    id, tenant_id, code, name, lifecycle_state,
                    revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', 1, ?, ?)
                """,
                applicationId, tenant.tenantId(), applicationCode, applicationCode,
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO catalog.application_target (
                    id, tenant_id, application_id, code, lifecycle_state,
                    revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', 1, ?, ?)
                """,
                targetId, tenant.tenantId(), applicationId, targetCode,
                Timestamp.from(NOW), Timestamp.from(NOW));
        return targetId;
    }
}
