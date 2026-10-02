package io.wyrmgate.iam.administration.notification;

import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassNotification;
import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassNotificationDeliveryException;
import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassNotificationPublisher;
import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassNotificationRepository;
import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassNotificationWork;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Durable at-least-once SECURITY_NOTIFICATION delivery outside break-glass activation transactions. */
public final class BreakGlassNotificationDeliveryService {

    private static final String UNKNOWN_FAILURE = "break_glass_notification_delivery_failed";

    private final AdministrativeBreakGlassNotificationRepository repository;
    private final AdministrativeBreakGlassNotificationPublisher publisher;
    private final BreakGlassNotificationProperties properties;
    private final Clock clock;

    public BreakGlassNotificationDeliveryService(
            AdministrativeBreakGlassNotificationRepository repository,
            AdministrativeBreakGlassNotificationPublisher publisher,
            BreakGlassNotificationProperties properties) {
        this(repository, publisher, properties, Clock.systemUTC());
    }

    BreakGlassNotificationDeliveryService(
            AdministrativeBreakGlassNotificationRepository repository,
            AdministrativeBreakGlassNotificationPublisher publisher,
            BreakGlassNotificationProperties properties,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DeliveryBatchResult deliverAvailable() {
        Instant now = clock.instant();
        List<AdministrativeBreakGlassNotificationWork> claimed = repository.claimPending(
                now, properties.effectiveClaimLease(), properties.effectiveBatchSize());

        int completed = 0;
        int retrying = 0;
        int manualRequired = 0;

        for (AdministrativeBreakGlassNotificationWork work : claimed) {
            try {
                publisher.publish(AdministrativeBreakGlassNotification.from(work));
                repository.markCompleted(
                        work.tenant(), work.obligationId(), work.attemptCount(), clock.instant());
                completed++;
            } catch (AdministrativeBreakGlassNotificationDeliveryException failure) {
                if (failure.retryable()
                        && work.attemptCount() < properties.effectiveMaxAttempts()) {
                    repository.markRetry(
                            work.tenant(),
                            work.obligationId(),
                            work.attemptCount(),
                            clock.instant().plus(retryDelay(work.attemptCount())),
                            failure.errorCode(),
                            clock.instant());
                    retrying++;
                } else {
                    repository.markManualRequired(
                            work.tenant(),
                            work.obligationId(),
                            work.attemptCount(),
                            failure.errorCode(),
                            clock.instant());
                    manualRequired++;
                }
            } catch (RuntimeException failure) {
                if (work.attemptCount() < properties.effectiveMaxAttempts()) {
                    repository.markRetry(
                            work.tenant(),
                            work.obligationId(),
                            work.attemptCount(),
                            clock.instant().plus(retryDelay(work.attemptCount())),
                            UNKNOWN_FAILURE,
                            clock.instant());
                    retrying++;
                } else {
                    repository.markManualRequired(
                            work.tenant(),
                            work.obligationId(),
                            work.attemptCount(),
                            UNKNOWN_FAILURE,
                            clock.instant());
                    manualRequired++;
                }
            }
        }

        return new DeliveryBatchResult(claimed.size(), completed, retrying, manualRequired);
    }

    private Duration retryDelay(int attemptCount) {
        long base = properties.effectiveRetryBaseDelay().toMillis();
        long max = properties.effectiveRetryMaxDelay().toMillis();
        long delay = base;
        for (int index = 1; index < attemptCount && delay < max; index++) {
            delay = delay > max / 2 ? max : delay * 2;
        }
        return Duration.ofMillis(Math.min(delay, max));
    }

    public record DeliveryBatchResult(
            int claimed,
            int completed,
            int retrying,
            int manualRequired) {
        public DeliveryBatchResult {
            if (claimed < 0 || completed < 0 || retrying < 0 || manualRequired < 0
                    || completed + retrying + manualRequired != claimed) {
                throw new IllegalArgumentException("invalid break-glass notification batch counts");
            }
        }
    }
}
