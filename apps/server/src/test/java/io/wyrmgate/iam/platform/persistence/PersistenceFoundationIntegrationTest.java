package io.wyrmgate.iam.platform.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.RegistrationKind;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository.ClaimedWork;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

class PersistenceFoundationIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");

    private static JdbcTemplate jdbc;
    private static IdGenerator idGenerator;
    private static JdbcTenantRepository tenants;
    private static JdbcOutboxRepository outbox;
    private static JdbcInboxRepository inbox;
    private static JdbcIdempotencyRepository idempotency;
    private static JdbcScheduledWorkRepository scheduledWork;
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
        inbox = new JdbcInboxRepository(jdbc, idGenerator);
        idempotency = new JdbcIdempotencyRepository(jdbc, idGenerator);
        scheduledWork = new JdbcScheduledWorkRepository(jdbc, idGenerator);
        transactions = new SpringTransactionExecutor(new DataSourceTransactionManager(dataSource));

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("9");
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @BeforeEach
    void clearPlatformRows() {
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
    void emptyDatabaseBootstrapCreatesAllCapabilitySchemas() {
        List<String> schemas = List.of(
                "identity",
                "catalog",
                "access",
                "governance",
                "credential",
                "integration",
                "administration",
                "audit",
                "platform");

        for (String schema : schemas) {
            Integer count = jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.schemata WHERE schema_name = ?",
                    Integer.class,
                    schema);
            assertThat(count).as("schema %s", schema).isEqualTo(1);
        }
    }

    @Test
    void tenantOwnedInfrastructureRejectsMissingTenantScope() {
        assertThatThrownBy(() -> jdbc.update(
                        """
                        INSERT INTO platform.inbox_message (
                            id, tenant_id, consumer_name, message_id, first_seen_at)
                        VALUES (?, NULL, 'consumer', 'message', ?)
                        """,
                        idGenerator.nextId(),
                        Timestamp.from(Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void optimisticRevisionRejectsStaleWrites() {
        Instant now = Instant.now();
        JdbcTenantRepository.Tenant tenant = tenants.create("Tenant A", now);

        JdbcTenantRepository.Tenant updated = tenants.rename(
                tenant.id(), "Tenant A renamed", tenant.revision(), now.plusSeconds(1));
        assertThat(updated.revision()).isEqualTo(2);

        assertThatThrownBy(() -> tenants.rename(
                        tenant.id(), "stale rename", tenant.revision(), now.plusSeconds(2)))
                .isInstanceOf(StaleWriteException.class);
        assertThat(tenants.get(tenant.id()).displayName()).isEqualTo("Tenant A renamed");
    }

    @Test
    void authoritativeMutationAndOutboxRollbackAtomically() {
        Instant now = Instant.now();
        JdbcTenantRepository.Tenant tenant = tenants.create("Atomic Tenant", now);
        TenantContext context = new TenantContext(tenant.id());
        UUID eventId = idGenerator.nextId();

        assertThatThrownBy(() -> transactions.required((Runnable) () -> {
                    tenants.rename(tenant.id(), "must rollback", tenant.revision(), now.plusSeconds(1));
                    outbox.append(
                            context,
                            new OutboxEvent(
                                    eventId,
                                    "platform.tenant-renamed",
                                    1,
                                    "tenant",
                                    tenant.id(),
                                    2L,
                                    now.plusSeconds(1),
                                    idGenerator.nextId(),
                                    null,
                                    "{\"displayNameChanged\":true}"),
                            now.plusSeconds(1));
                    throw new IllegalStateException("force rollback");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(tenants.get(tenant.id()).revision()).isEqualTo(1);
        assertThat(outbox.countPending(context)).isZero();
    }

    @Test
    void inboxDeduplicatesPerTenantConsumerAndMessage() {
        Instant now = Instant.now();
        JdbcTenantRepository.Tenant firstTenant = tenants.create("Tenant One", now);
        JdbcTenantRepository.Tenant secondTenant = tenants.create("Tenant Two", now);
        TenantContext first = new TenantContext(firstTenant.id());
        TenantContext second = new TenantContext(secondTenant.id());

        assertThat(inbox.tryAccept(first, "desired-access", "event-42", now)).isTrue();
        assertThat(inbox.tryAccept(first, "desired-access", "event-42", now.plusMillis(1))).isFalse();
        assertThat(inbox.tryAccept(first, "audit-projection", "event-42", now)).isTrue();
        assertThat(inbox.tryAccept(second, "desired-access", "event-42", now)).isTrue();
    }

    @Test
    void idempotencyReplaysSameFingerprintAndRejectsDifferentRequest() {
        Instant now = Instant.now();
        JdbcTenantRepository.Tenant tenant = tenants.create("Idempotency Tenant", now);
        TenantContext context = new TenantContext(tenant.id());
        RequestFingerprint firstFingerprint = RequestFingerprint.sha256(
                "canonical-request-a".getBytes(StandardCharsets.UTF_8));
        RequestFingerprint differentFingerprint = RequestFingerprint.sha256(
                "canonical-request-b".getBytes(StandardCharsets.UTF_8));

        var first = idempotency.register(
                context, "access.assign", "request-123", firstFingerprint, now, now.plusSeconds(300));
        var replay = idempotency.register(
                context, "access.assign", "request-123", firstFingerprint, now.plusMillis(1), now.plusSeconds(300));

        assertThat(first.kind()).isEqualTo(RegistrationKind.NEW);
        assertThat(replay.kind()).isEqualTo(RegistrationKind.REPLAY);
        assertThat(replay.recordId()).isEqualTo(first.recordId());
        assertThatThrownBy(() -> idempotency.register(
                        context,
                        "access.assign",
                        "request-123",
                        differentFingerprint,
                        now.plusMillis(2),
                        now.plusSeconds(300)))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void expiredLeaseCanBeReclaimedAndStaleWorkerCannotComplete() {
        Instant now = Instant.now();
        JdbcTenantRepository.Tenant tenant = tenants.create("Lease Tenant", now);
        TenantContext context = new TenantContext(tenant.id());
        assertThat(scheduledWork.enqueue(
                        context,
                        "projection.refresh",
                        "lease-recovery",
                        null,
                        now.minusSeconds(1),
                        now))
                .isTrue();

        ClaimedWork firstClaim = scheduledWork
                .claimDue(context, "worker-a", now, Duration.ofSeconds(1), 1)
                .getFirst();
        Instant afterExpiry = now.plusSeconds(2);

        assertThatThrownBy(() -> scheduledWork.markCompleted(
                        context, firstClaim.id(), "worker-a", afterExpiry))
                .isInstanceOf(IllegalStateException.class);

        ClaimedWork reclaimed = scheduledWork
                .claimDue(context, "worker-b", afterExpiry, Duration.ofMinutes(1), 1)
                .getFirst();
        assertThat(reclaimed.id()).isEqualTo(firstClaim.id());
        assertThat(reclaimed.attemptCount()).isEqualTo(2);
        scheduledWork.markCompleted(context, reclaimed.id(), "worker-b", afterExpiry.plusSeconds(1));
    }

    @Test
    void concurrentWorkersClaimEachDueItemAtMostOnce() throws Exception {
        Instant now = Instant.now();
        JdbcTenantRepository.Tenant tenant = tenants.create("Worker Tenant", now);
        TenantContext context = new TenantContext(tenant.id());

        for (int index = 0; index < 20; index++) {
            assertThat(scheduledWork.enqueue(
                            context,
                            "projection.refresh",
                            "work-" + index,
                            null,
                            now.minusSeconds(1),
                            now))
                    .isTrue();
        }

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<List<ClaimedWork>> first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return scheduledWork.claimDue(context, "worker-a", now, Duration.ofMinutes(1), 10);
            });
            Future<List<ClaimedWork>> second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return scheduledWork.claimDue(context, "worker-b", now, Duration.ofMinutes(1), 10);
            });

            ready.await();
            start.countDown();
            List<ClaimedWork> firstClaims = first.get();
            List<ClaimedWork> secondClaims = second.get();

            assertThat(firstClaims).hasSize(10);
            assertThat(secondClaims).hasSize(10);
            Set<UUID> allIds = new HashSet<>();
            firstClaims.forEach(work -> assertThat(allIds.add(work.id())).isTrue());
            secondClaims.forEach(work -> assertThat(allIds.add(work.id())).isTrue());
            assertThat(allIds).hasSize(20);
        } finally {
            executor.shutdownNow();
        }
    }
}
