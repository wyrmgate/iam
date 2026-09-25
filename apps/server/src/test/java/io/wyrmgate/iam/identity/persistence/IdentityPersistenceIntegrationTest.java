package io.wyrmgate.iam.identity.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.wyrmgate.iam.identity.application.IdentityCommandService;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class IdentityPersistenceIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");

    private static JdbcTemplate jdbc;
    private static IdGenerator idGenerator;
    private static JdbcTenantRepository tenants;
    private static JdbcOutboxRepository outbox;
    private static JdbcIdentityRepository identities;
    private static IdentityCommandService commands;
    private static TransactionExecutor transactions;

    @BeforeAll
    static void startPostgresAndMigrateFromEmptyDatabase() {
        POSTGRES.start();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());

        Flyway flyway = Flyway.configure().dataSource(dataSource).load();
        flyway.migrate();
        flyway.validate();

        jdbc = new JdbcTemplate(dataSource);
        idGenerator = new UuidV7Generator();
        tenants = new JdbcTenantRepository(jdbc, idGenerator);
        outbox = new JdbcOutboxRepository(jdbc);
        identities = new JdbcIdentityRepository(jdbc);
        transactions = new SpringTransactionExecutor(new DataSourceTransactionManager(dataSource));
        commands = new IdentityCommandService(
                identities,
                new JdbcIdentityFactSink(outbox, idGenerator),
                idGenerator,
                transactions);

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("22");
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
    void createsEveryCanonicalIdentityTypeWithExactlyOneCompatibleProfileAndFact() {
        Instant now = Instant.now();
        TenantContext tenant = tenant("Identity Tenant", now);

        Identity person = commands.create(
                tenant,
                IdentityType.PERSON,
                new IdentityProfile.PersonProfile(),
                IdentityLifecycleState.ACTIVE,
                "Ada Person",
                now,
                idGenerator.nextId(),
                null);
        Identity service = commands.create(
                tenant,
                IdentityType.SERVICE,
                new IdentityProfile.ServiceProfile(),
                IdentityLifecycleState.PENDING,
                "Billing Service",
                now.plusMillis(1),
                idGenerator.nextId(),
                null);
        Identity workload = commands.create(
                tenant,
                IdentityType.WORKLOAD,
                new IdentityProfile.WorkloadProfile(),
                IdentityLifecycleState.ACTIVE,
                "Settlement Workload",
                now.plusMillis(2),
                idGenerator.nextId(),
                null);

        assertThat(identities.findById(tenant, person.id())).contains(person);
        assertThat(identities.findById(tenant, service.id())).contains(service);
        assertThat(identities.findById(tenant, workload.id())).contains(workload);

        assertThat(profileCount("person_profile", tenant, person.id())).isEqualTo(1);
        assertThat(profileCount("service_profile", tenant, person.id())).isZero();
        assertThat(profileCount("workload_profile", tenant, person.id())).isZero();
        assertThat(profileCount("service_profile", tenant, service.id())).isEqualTo(1);
        assertThat(profileCount("workload_profile", tenant, workload.id())).isEqualTo(1);
        assertThat(outbox.countPending(tenant)).isEqualTo(3);

        Integer createdFacts = jdbc.queryForObject(
                """
                SELECT count(*) FROM platform.outbox_event
                WHERE tenant_id = ?
                  AND event_type = 'identity.identity-created'
                  AND aggregate_type = 'identity'
                  AND aggregate_revision = 1
                """,
                Integer.class,
                tenant.tenantId());
        assertThat(createdFacts).isEqualTo(3);
    }

    @Test
    void rejectsIncompatibleProfileBeforeAnyAuthoritativeWrite() {
        Instant now = Instant.now();
        TenantContext tenant = tenant("Profile Tenant", now);

        assertThatThrownBy(() -> commands.create(
                        tenant,
                        IdentityType.PERSON,
                        new IdentityProfile.ServiceProfile(),
                        IdentityLifecycleState.ACTIVE,
                        "Invalid Person",
                        now,
                        idGenerator.nextId(),
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("profile must be compatible");

        assertThat(identityCount(tenant)).isZero();
        assertThat(outbox.countPending(tenant)).isZero();
    }

    @Test
    void compositeProfileReferenceRejectsCrossTenantIdentity() {
        Instant now = Instant.now();
        TenantContext first = tenant("First Tenant", now);
        TenantContext second = tenant("Second Tenant", now);
        UUID identityId = idGenerator.nextId();

        jdbc.update(
                """
                INSERT INTO identity.identity (
                    id, tenant_id, identity_type, lifecycle_state, display_name,
                    revision, created_at, updated_at)
                VALUES (?, ?, 'PERSON', 'ACTIVE', 'Raw Identity', 1, ?, ?)
                """,
                identityId,
                first.tenantId(),
                Timestamp.from(now),
                Timestamp.from(now));

        assertThatThrownBy(() -> jdbc.update(
                        """
                        INSERT INTO identity.person_profile (
                            identity_id, tenant_id, identity_type, created_at)
                        VALUES (?, ?, 'PERSON', ?)
                        """,
                        identityId,
                        second.tenantId(),
                        Timestamp.from(now)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void tenantScopeAndOptimisticRevisionProtectAuthoritativeMutation() {
        Instant now = Instant.now();
        TenantContext owner = tenant("Owner Tenant", now);
        TenantContext other = tenant("Other Tenant", now);
        Identity created = commands.create(
                owner,
                IdentityType.PERSON,
                new IdentityProfile.PersonProfile(),
                IdentityLifecycleState.ACTIVE,
                "Original Name",
                now,
                idGenerator.nextId(),
                null);

        assertThat(identities.findById(other, created.id())).isEmpty();

        Identity updated = commands.changeDisplayName(
                owner,
                created.id(),
                "Updated Name",
                created.revision(),
                now.plusSeconds(1),
                idGenerator.nextId(),
                null);
        assertThat(updated.revision()).isEqualTo(2);
        assertThat(updated.displayName()).isEqualTo("Updated Name");
        assertThat(outbox.countPending(owner)).isEqualTo(2);

        assertThatThrownBy(() -> commands.changeDisplayName(
                        owner,
                        created.id(),
                        "Stale Name",
                        created.revision(),
                        now.plusSeconds(2),
                        idGenerator.nextId(),
                        null))
                .isInstanceOf(StaleWriteException.class);

        Identity persisted = identities.findById(owner, created.id()).orElseThrow();
        assertThat(persisted.displayName()).isEqualTo("Updated Name");
        assertThat(persisted.revision()).isEqualTo(2);
        assertThat(outbox.countPending(owner)).isEqualTo(2);
    }

    @Test
    void identityMutationAndOutboxFactRollbackTogether() {
        Instant now = Instant.now();
        TenantContext tenant = tenant("Atomic Identity Tenant", now);
        Identity identity = new Identity(
                idGenerator.nextId(),
                IdentityType.SERVICE,
                new IdentityProfile.ServiceProfile(),
                IdentityLifecycleState.PENDING,
                "Rollback Service",
                1,
                now,
                now);
        JdbcIdentityFactSink facts = new JdbcIdentityFactSink(outbox, idGenerator);

        assertThatThrownBy(() -> transactions.required((Runnable) () -> {
                    identities.insert(tenant, identity);
                    facts.identityCreated(tenant, identity, idGenerator.nextId(), null);
                    throw new IllegalStateException("force identity rollback");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("force identity rollback");

        assertThat(identities.findById(tenant, identity.id())).isEmpty();
        assertThat(outbox.countPending(tenant)).isZero();
    }

    private static TenantContext tenant(String displayName, Instant now) {
        return new TenantContext(tenants.create(displayName, now).id());
    }

    private static int identityCount(TenantContext tenant) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM identity.identity WHERE tenant_id = ?",
                Integer.class,
                tenant.tenantId());
        return count == null ? 0 : count;
    }

    private static int profileCount(String table, TenantContext tenant, UUID identityId) {
        if (!table.equals("person_profile")
                && !table.equals("service_profile")
                && !table.equals("workload_profile")) {
            throw new IllegalArgumentException("unexpected profile table");
        }
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM identity." + table + " WHERE tenant_id = ? AND identity_id = ?",
                Integer.class,
                tenant.tenantId(),
                identityId);
        return count == null ? 0 : count;
    }
}
