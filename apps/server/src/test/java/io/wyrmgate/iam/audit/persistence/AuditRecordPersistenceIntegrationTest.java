package io.wyrmgate.iam.audit.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.wyrmgate.iam.audit.application.AuditCommandService;
import io.wyrmgate.iam.audit.application.AuditQueryModels.AuditFilter;
import io.wyrmgate.iam.audit.application.AuditQueryService;
import io.wyrmgate.iam.audit.application.AuditRecordConflictException;
import io.wyrmgate.iam.audit.application.AuditRecordDraft;
import io.wyrmgate.iam.audit.domain.AuditOutcome;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.JdbcTenantRepository;
import io.wyrmgate.iam.platform.persistence.SpringTransactionExecutor;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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

class AuditRecordPersistenceIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");

    private static JdbcTemplate jdbc;
    private static JdbcTenantRepository tenants;
    private static IdGenerator ids;
    private static JdbcAuditRecordRepository repository;
    private static TransactionExecutor transactions;

    @BeforeAll
    static void start() {
        POSTGRES.start();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(dataSource).load();
        flyway.migrate();
        flyway.validate();

        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new JdbcTenantRepository(jdbc, ids);
        repository = new JdbcAuditRecordRepository(jdbc);
        transactions = new SpringTransactionExecutor(new DataSourceTransactionManager(dataSource));

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("52");
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @BeforeEach
    void reset() {
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
    void appendIsReplaySafeConflictDetectingAndTenantIsolated() {
        Instant occurredAt = Instant.parse("2026-09-30T14:00:00Z");
        TenantContext tenant = tenant("Audit A", occurredAt);
        TenantContext other = tenant("Audit B", occurredAt);
        UUID recordId = ids.nextId();
        UUID actorId = ids.nextId();
        UUID resourceId = ids.nextId();
        UUID correlationId = ids.nextId();
        UUID causationId = ids.nextId();

        AuditRecordDraft draft = new AuditRecordDraft(
                recordId,
                occurredAt,
                actorId,
                "identity:merge",
                "identity",
                resourceId,
                AuditOutcome.SUCCESS,
                correlationId,
                causationId);

        AuditCommandService first = service(Instant.parse("2026-09-30T14:00:01Z"));
        var created = first.append(tenant, draft);
        assertThat(created.recordedAt()).isEqualTo(Instant.parse("2026-09-30T14:00:01Z"));

        AuditCommandService replay = service(Instant.parse("2026-09-30T15:00:00Z"));
        var replayed = replay.append(tenant, draft);
        assertThat(replayed).isEqualTo(created);
        assertThat(repository.findById(other, recordId)).isEmpty();

        AuditRecordDraft conflicting = new AuditRecordDraft(
                recordId,
                occurredAt,
                actorId,
                "identity:merge",
                "identity",
                resourceId,
                AuditOutcome.DENIED,
                correlationId,
                causationId);
        assertThatThrownBy(() -> replay.append(tenant, conflicting))
                .isInstanceOf(AuditRecordConflictException.class);

        assertThatThrownBy(() -> replay.append(other, draft))
                .isInstanceOf(AuditRecordConflictException.class);

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_record WHERE id = ?",
                Integer.class,
                recordId);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void persistedAuditRowsRejectUpdateAndDelete() {
        Instant occurredAt = Instant.parse("2026-09-30T14:30:00Z");
        TenantContext tenant = tenant("Audit Immutable", occurredAt);
        var record = service(occurredAt.plusSeconds(1)).append(
                tenant,
                draft(
                        occurredAt,
                        ids.nextId(),
                        "identity:update",
                        "identity",
                        ids.nextId(),
                        AuditOutcome.SUCCESS,
                        ids.nextId()));

        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE audit.audit_record SET outcome = 'FAILURE' WHERE tenant_id = ? AND id = ?",
                        tenant.tenantId(),
                        record.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "DELETE FROM audit.audit_record WHERE tenant_id = ? AND id = ?",
                        tenant.tenantId(),
                        record.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);

        assertThat(repository.findById(tenant, record.id())).contains(record);
    }

    @Test
    void keysetOrderingUsesDescendingIdAsOccurrenceTimeTiebreaker() {
        Instant occurredAt = Instant.parse("2026-09-30T14:45:00Z");
        TenantContext tenant = tenant("Audit Tie", occurredAt);
        AuditCommandService service = service(occurredAt.plusSeconds(1));
        UUID lowerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID higherId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        service.append(tenant, new AuditRecordDraft(
                lowerId, occurredAt, null, "audit:test", "audit-record", null,
                AuditOutcome.SUCCESS, null, null));
        service.append(tenant, new AuditRecordDraft(
                higherId, occurredAt, null, "audit:test", "audit-record", null,
                AuditOutcome.SUCCESS, null, null));

        AuditQueryService queries = new AuditQueryService(repository);
        var firstPage = queries.list(tenant, AuditFilter.none(), null, 1);
        assertThat(firstPage.items()).extracting(record -> record.id()).containsExactly(higherId);
        assertThat(firstPage.nextPosition()).isNotNull();

        var secondPage = queries.list(tenant, AuditFilter.none(), firstPage.nextPosition(), 1);
        assertThat(secondPage.items()).extracting(record -> record.id()).containsExactly(lowerId);
    }

    @Test
    void boundedQueryUsesDeterministicNewestFirstKeysetAndExactFilters() {
        Instant base = Instant.parse("2026-09-30T15:00:00Z");
        TenantContext tenant = tenant("Audit Query", base);
        UUID actor = ids.nextId();
        UUID otherActor = ids.nextId();
        UUID correlation = ids.nextId();
        AuditCommandService service = service(base.plusSeconds(100));

        var first = service.append(tenant, draft(
                base.plusSeconds(1), actor, "identity:read", "identity",
                ids.nextId(), AuditOutcome.SUCCESS, correlation));
        var second = service.append(tenant, draft(
                base.plusSeconds(2), actor, "identity:update", "identity",
                ids.nextId(), AuditOutcome.DENIED, correlation));
        var third = service.append(tenant, draft(
                base.plusSeconds(3), actor, "identity:merge", "identity",
                ids.nextId(), AuditOutcome.SUCCESS, correlation));
        service.append(tenant, draft(
                base.plusSeconds(4), otherActor, "credential:read", "credential",
                ids.nextId(), AuditOutcome.SUCCESS, ids.nextId()));

        AuditQueryService queries = new AuditQueryService(repository);
        AuditFilter actorFilter = new AuditFilter(actor, null, null, null, null, null);
        var page1 = queries.list(tenant, actorFilter, null, 2);
        assertThat(page1.items()).extracting(record -> record.id())
                .containsExactly(third.id(), second.id());
        assertThat(page1.nextPosition()).isNotNull();

        var page2 = queries.list(tenant, actorFilter, page1.nextPosition(), 2);
        assertThat(page2.items()).extracting(record -> record.id())
                .containsExactly(first.id());
        assertThat(page2.nextPosition()).isNull();

        var denied = queries.list(
                tenant,
                new AuditFilter(null, null, "identity", null, AuditOutcome.DENIED, correlation),
                null,
                10);
        assertThat(denied.items()).extracting(record -> record.id())
                .containsExactly(second.id());

        assertThatThrownBy(() -> queries.list(tenant, AuditFilter.none(), null, 201))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private AuditRecordDraft draft(
            Instant occurredAt,
            UUID actorId,
            String actionType,
            String resourceType,
            UUID resourceId,
            AuditOutcome outcome,
            UUID correlationId) {
        return new AuditRecordDraft(
                ids.nextId(),
                occurredAt,
                actorId,
                actionType,
                resourceType,
                resourceId,
                outcome,
                correlationId,
                null);
    }

    private AuditCommandService service(Instant recordedAt) {
        return new AuditCommandService(
                repository,
                transactions,
                Clock.fixed(recordedAt, ZoneOffset.UTC));
    }

    private TenantContext tenant(String name, Instant now) {
        return new TenantContext(tenants.create(name, now).id());
    }
}
