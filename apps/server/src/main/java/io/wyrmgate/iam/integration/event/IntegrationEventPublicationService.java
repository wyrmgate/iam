package io.wyrmgate.iam.integration.event;

import io.wyrmgate.iam.platform.persistence.ClaimedOutboxEvent;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.DoubleSupplier;

/**
 * Claims internal Identity facts, curates public events and delivers them outside
 * the database statement that leased the outbox rows.
 */
public final class IntegrationEventPublicationService {

    private static final String MAPPING_FAILURE = "identity_event_mapping_failed";
    private static final String PUBLICATION_FAILURE = "integration_event_publication_failed";

    private final JdbcOutboxRepository outbox;
    private final IdentityIntegrationEventMapper mapper;
    private final IntegrationEventJsonEncoder encoder;
    private final IntegrationEventPublisher publisher;
    private final IntegrationEventPublicationProperties properties;
    private final Clock clock;
    private final DoubleSupplier jitter;

    public IntegrationEventPublicationService(
            JdbcOutboxRepository outbox,
            IdentityIntegrationEventMapper mapper,
            IntegrationEventJsonEncoder encoder,
            IntegrationEventPublisher publisher,
            IntegrationEventPublicationProperties properties) {
        this(outbox, mapper, encoder, publisher, properties, Clock.systemUTC(), Math::random);
    }

    IntegrationEventPublicationService(
            JdbcOutboxRepository outbox,
            IdentityIntegrationEventMapper mapper,
            IntegrationEventJsonEncoder encoder,
            IntegrationEventPublisher publisher,
            IntegrationEventPublicationProperties properties,
            Clock clock,
            DoubleSupplier jitter) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.encoder = Objects.requireNonNull(encoder, "encoder");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.jitter = Objects.requireNonNull(jitter, "jitter");
    }

    public PublicationBatchResult publishAvailable() {
        Instant now = clock.instant();
        List<ClaimedOutboxEvent> claimed = outbox.claimPending(
                mapper.supportedInternalEventTypes(),
                now,
                properties.effectiveClaimLease(),
                properties.effectiveBatchSize());

        int published = 0;
        int failed = 0;
        for (ClaimedOutboxEvent item : claimed) {
            OutboundIntegrationEvent outbound;
            try {
                IdentityPublicIntegrationEvents.Event publicEvent = mapper.map(item);
                outbound = encoder.encode(publicEvent);
            } catch (IllegalArgumentException mappingFailure) {
                outbox.markTerminalFailure(item.tenant(), item.event().eventId(), MAPPING_FAILURE);
                failed++;
                continue;
            }

            try {
                publisher.publish(outbound);
                outbox.markPublished(item.tenant(), item.event().eventId(), clock.instant());
                published++;
            } catch (RuntimeException publicationFailure) {
                scheduleRetry(item, PUBLICATION_FAILURE);
                failed++;
            }
        }
        return new PublicationBatchResult(claimed.size(), published, failed);
    }

    private void scheduleRetry(ClaimedOutboxEvent item, String errorCode) {
        Duration delay = retryDelay(item.attemptCount());
        outbox.markFailed(
                item.tenant(),
                item.event().eventId(),
                clock.instant().plus(delay),
                errorCode);
    }

    private Duration retryDelay(int attemptCount) {
        long baseMillis = properties.effectiveRetryBaseDelay().toMillis();
        long maxMillis = properties.effectiveRetryMaxDelay().toMillis();
        long exponential = baseMillis;
        for (int index = 1; index < attemptCount && exponential < maxMillis; index++) {
            exponential = exponential > maxMillis / 2 ? maxMillis : exponential * 2;
        }

        double random = jitter.getAsDouble();
        if (Double.isNaN(random) || random < 0.0 || random >= 1.0) {
            random = 0.5;
        }
        double factor = 0.5 + random;
        long jittered = Math.max(1L, Math.round(exponential * factor));
        return Duration.ofMillis(Math.min(maxMillis, jittered));
    }

    public record PublicationBatchResult(int claimed, int published, int failed) {
        public PublicationBatchResult {
            if (claimed < 0 || published < 0 || failed < 0 || published + failed != claimed) {
                throw new IllegalArgumentException("invalid publication batch counts");
            }
        }
    }
}
