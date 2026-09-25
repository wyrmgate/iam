package io.wyrmgate.iam.integration.event;

import static org.assertj.core.api.Assertions.assertThat;

import io.wyrmgate.iam.identity.application.IdentityCommandService;
import io.wyrmgate.iam.identity.domain.Identity;
import io.wyrmgate.iam.identity.domain.IdentityLifecycleState;
import io.wyrmgate.iam.identity.domain.IdentityProfile;
import io.wyrmgate.iam.identity.domain.IdentityType;
import io.wyrmgate.iam.identity.persistence.JdbcIdentityFactSink;
import io.wyrmgate.iam.identity.persistence.JdbcIdentityRepository;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.JdbcTenantRepository;
import io.wyrmgate.iam.platform.persistence.SpringTransactionExecutor;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class IdentityIntegrationEventPublicationIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");
    private static final Instant BASE_TIME = Instant.parse("2026-09-21T04:10:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static JdbcOutboxRepository outbox;
    private static JdbcIdentityRepository identities;
    private static IdentityCommandService commands;

    @BeforeAll
    static void startPostgres() {
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
        identities = new JdbcIdentityRepository(jdbc);
        TransactionExecutor transactions =
                new SpringTransactionExecutor(new DataSourceTransactionManager(dataSource));
        commands = new IdentityCommandService(
                identities,
                new JdbcIdentityFactSink(outbox, ids),
                ids,
                transactions);

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("19");
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @BeforeEach
    void clearDatabase() {
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
    void publishesCuratedEventsAndMarksInternalFactsPublished() {
        TenantContext tenant = tenant("Publication Tenant");
        Identity identity = commands.create(
                tenant,
                IdentityType.SERVICE,
                new IdentityProfile.ServiceProfile(),
                IdentityLifecycleState.PENDING,
                "Do Not Publish This Name",
                BASE_TIME,
                ids.nextId(),
                null);
        commands.changeDisplayName(
                tenant,
                identity.id(),
                "Still Private",
                identity.revision(),
                BASE_TIME.plusSeconds(1),
                ids.nextId(),
                null);

        RecordingPublisher publisher = new RecordingPublisher(false);
        IntegrationEventPublicationService service = service(
                publisher,
                Clock.fixed(BASE_TIME.plusSeconds(2), ZoneOffset.UTC),
                () -> 0.5);

        var result = service.publishAvailable();

        assertThat(result.claimed()).isEqualTo(2);
        assertThat(result.published()).isEqualTo(2);
        assertThat(result.failed()).isZero();
        assertThat(publisher.events).hasSize(2);
        assertThat(publisher.events.stream().map(OutboundIntegrationEvent::address).toList())
                .containsExactly(
                        "iam.identity.created.v1",
                        "iam.identity.metadata-changed.v1");

        String combined = publisher.events.stream()
                .map(event -> new String(event.payload(), StandardCharsets.UTF_8))
                .reduce("", (left, right) -> left + right);
        assertThat(combined).contains("\"lifecycleState\":\"PENDING\"");
        assertThat(combined).contains("\"changedFields\":[\"displayName\"]");
        assertThat(combined).doesNotContain("Do Not Publish This Name");
        assertThat(combined).doesNotContain("Still Private");

        Integer published = jdbc.queryForObject(
                "SELECT count(*) FROM platform.outbox_event WHERE publication_state = 'PUBLISHED'",
                Integer.class);
        Long attempts = jdbc.queryForObject(
                "SELECT sum(attempt_count) FROM platform.outbox_event",
                Long.class);
        assertThat(published).isEqualTo(2);
        assertThat(attempts).isEqualTo(2L);
    }

    @Test
    void transientPublisherFailureLeavesFactPendingWithNormalizedRetryAndCanReplay() {
        TenantContext tenant = tenant("Retry Tenant");
        commands.create(
                tenant,
                IdentityType.PERSON,
                new IdentityProfile.PersonProfile(),
                IdentityLifecycleState.ACTIVE,
                "Private Person Name",
                BASE_TIME,
                ids.nextId(),
                null);

        IntegrationEventPublicationService failing = service(
                new RecordingPublisher(true),
                Clock.fixed(BASE_TIME.plusSeconds(1), ZoneOffset.UTC),
                () -> 0.5);
        var failed = failing.publishAvailable();

        assertThat(failed.claimed()).isEqualTo(1);
        assertThat(failed.failed()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT publication_state FROM platform.outbox_event", String.class))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT attempt_count FROM platform.outbox_event", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT last_error_code FROM platform.outbox_event", String.class))
                .isEqualTo("integration_event_publication_failed");

        Instant nextAttempt = jdbc.queryForObject(
                "SELECT next_attempt_at FROM platform.outbox_event",
                (rs, rowNum) -> rs.getTimestamp(1).toInstant());
        assertThat(nextAttempt).isEqualTo(BASE_TIME.plusSeconds(6));

        RecordingPublisher recoveredPublisher = new RecordingPublisher(false);
        IntegrationEventPublicationService recovered = service(
                recoveredPublisher,
                Clock.fixed(BASE_TIME.plusSeconds(7), ZoneOffset.UTC),
                () -> 0.5);
        var replayed = recovered.publishAvailable();

        assertThat(replayed.published()).isEqualTo(1);
        assertThat(recoveredPublisher.events).hasSize(1);
        assertThat(jdbc.queryForObject(
                "SELECT publication_state FROM platform.outbox_event", String.class))
                .isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject(
                "SELECT attempt_count FROM platform.outbox_event", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void malformedInternalFactMovesToTerminalFailedStateWithoutRetry() {
        TenantContext tenant = tenant("Malformed Fact Tenant");
        outbox.append(
                tenant,
                new io.wyrmgate.iam.platform.persistence.OutboxEvent(
                        ids.nextId(),
                        IdentityIntegrationEventMapper.IDENTITY_CREATED_FACT,
                        1,
                        "identity",
                        ids.nextId(),
                        1L,
                        BASE_TIME,
                        ids.nextId(),
                        null,
                        "{\"identityType\":\"PERSON\"}"),
                BASE_TIME);

        IntegrationEventPublicationService service = service(
                new RecordingPublisher(false),
                Clock.fixed(BASE_TIME.plusSeconds(1), ZoneOffset.UTC),
                () -> 0.5);

        var result = service.publishAvailable();

        assertThat(result.claimed()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT publication_state FROM platform.outbox_event", String.class))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT last_error_code FROM platform.outbox_event", String.class))
                .isEqualTo("identity_event_mapping_failed");
        java.sql.Timestamp nextAttemptAt = jdbc.queryForObject(
                "SELECT next_attempt_at FROM platform.outbox_event",
                (rs, rowNum) -> rs.getTimestamp(1));
        assertThat(nextAttemptAt).isNull();
    }

    @Test
    void unrelatedInternalFactsAreNotClaimedForPublicIdentityPublication() {
        TenantContext tenant = tenant("Unrelated Fact Tenant");
        outbox.append(
                tenant,
                new io.wyrmgate.iam.platform.persistence.OutboxEvent(
                        ids.nextId(),
                        "administration.initial-admin-bootstrapped",
                        1,
                        null,
                        null,
                        null,
                        BASE_TIME,
                        ids.nextId(),
                        null,
                        "{}"),
                BASE_TIME);

        RecordingPublisher publisher = new RecordingPublisher(false);
        var result = service(
                publisher,
                Clock.fixed(BASE_TIME.plusSeconds(1), ZoneOffset.UTC),
                () -> 0.5)
                .publishAvailable();

        assertThat(result.claimed()).isZero();
        assertThat(publisher.events).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT publication_state FROM platform.outbox_event", String.class))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT attempt_count FROM platform.outbox_event", Integer.class))
                .isZero();
    }

    @Test
    void abandonedClaimIsReclaimableOnlyAfterLeaseExpiry() {
        TenantContext tenant = tenant("Lease Recovery Tenant");
        commands.create(
                tenant,
                IdentityType.WORKLOAD,
                new IdentityProfile.WorkloadProfile(),
                IdentityLifecycleState.ACTIVE,
                "Private Workload",
                BASE_TIME,
                ids.nextId(),
                null);

        var first = outbox.claimPending(
                new IdentityIntegrationEventMapper().supportedInternalEventTypes(),
                BASE_TIME.plusSeconds(1),
                Duration.ofSeconds(30),
                10);
        assertThat(first).hasSize(1);
        assertThat(first.getFirst().attemptCount()).isEqualTo(1);

        var beforeExpiry = outbox.claimPending(
                new IdentityIntegrationEventMapper().supportedInternalEventTypes(),
                BASE_TIME.plusSeconds(20),
                Duration.ofSeconds(30),
                10);
        assertThat(beforeExpiry).isEmpty();

        var reclaimed = outbox.claimPending(
                new IdentityIntegrationEventMapper().supportedInternalEventTypes(),
                BASE_TIME.plusSeconds(32),
                Duration.ofSeconds(30),
                10);
        assertThat(reclaimed).hasSize(1);
        assertThat(reclaimed.getFirst().event().eventId()).isEqualTo(first.getFirst().event().eventId());
        assertThat(reclaimed.getFirst().attemptCount()).isEqualTo(2);
    }

    @Test
    void terminalAdapterFailureMovesFactToFailedWithoutRetry() {
        TenantContext tenant = tenant("Terminal Webhook Tenant");
        commands.create(
                tenant,
                IdentityType.PERSON,
                new IdentityProfile.PersonProfile(),
                IdentityLifecycleState.ACTIVE,
                "Private Terminal Name",
                BASE_TIME,
                ids.nextId(),
                null);

        IntegrationEventPublisher terminalPublisher = event -> {
            throw new IntegrationEventDeliveryException(false, "webhook_http_terminal");
        };
        var result = service(
                terminalPublisher,
                Clock.fixed(BASE_TIME.plusSeconds(1), ZoneOffset.UTC),
                () -> 0.5)
                .publishAvailable();

        assertThat(result.claimed()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT publication_state FROM platform.outbox_event", String.class))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT last_error_code FROM platform.outbox_event", String.class))
                .isEqualTo("webhook_http_terminal");
        java.sql.Timestamp nextAttemptAt = jdbc.queryForObject(
                "SELECT next_attempt_at FROM platform.outbox_event",
                (rs, rowNum) -> rs.getTimestamp(1));
        assertThat(nextAttemptAt).isNull();
    }

    private static IntegrationEventPublicationService service(
            IntegrationEventPublisher publisher,
            Clock clock,
            java.util.function.DoubleSupplier jitter) {
        return new IntegrationEventPublicationService(
                outbox,
                new IdentityIntegrationEventMapper(),
                new IntegrationEventJsonEncoder(),
                publisher,
                new IntegrationEventPublicationProperties(
                        true,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(30),
                        50,
                        Duration.ofSeconds(5),
                        Duration.ofMinutes(5)),
                clock,
                jitter);
    }

    private static TenantContext tenant(String name) {
        return new TenantContext(tenants.create(name, BASE_TIME).id());
    }

    private static final class RecordingPublisher implements IntegrationEventPublisher {
        private final boolean fail;
        private final List<OutboundIntegrationEvent> events = new ArrayList<>();

        private RecordingPublisher(boolean fail) {
            this.fail = fail;
        }

        @Override
        public void publish(OutboundIntegrationEvent event) {
            if (fail) {
                throw new IllegalStateException("provider detail must never be persisted");
            }
            events.add(event);
        }
    }
}
