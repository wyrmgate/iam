package io.wyrmgate.iam.administration.notification;

import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassNotificationDeliveryException;
import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassNotificationPublisher;
import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassNotificationScheduler;
import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassRepository;
import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassSecurityNotification;
import io.wyrmgate.iam.administration.domain.AdministrativeBreakGlassObligationState;
import io.wyrmgate.iam.administration.domain.AdministrativeBreakGlassObligationType;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository.ClaimedTenantWork;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.DoubleSupplier;

/**
 * Claims Platform technical work, re-reads Administration obligation state, and performs
 * ADR-0033 delivery outside the authoritative break-glass activation transaction.
 */
public final class BreakGlassNotificationDeliveryService {

    private static final String UNKNOWN_FAILURE = "break_glass_notification_delivery_failed";
    private static final String INVALID_WORK = "break_glass_notification_invalid_work";
    private static final String OPERATION_MISSING = "break_glass_notification_operation_missing";

    private final JdbcScheduledWorkRepository scheduledWork;
    private final AdministrativeBreakGlassRepository breakGlass;
    private final AdministrativeBreakGlassNotificationPublisher publisher;
    private final BreakGlassNotificationProperties properties;
    private final Clock clock;
    private final DoubleSupplier jitter;
    private final String leaseOwner;

    public BreakGlassNotificationDeliveryService(
            JdbcScheduledWorkRepository scheduledWork,
            AdministrativeBreakGlassRepository breakGlass,
            AdministrativeBreakGlassNotificationPublisher publisher,
            BreakGlassNotificationProperties properties) {
        this(
                scheduledWork,
                breakGlass,
                publisher,
                properties,
                Clock.systemUTC(),
                Math::random,
                "break-glass-notification-" + UUID.randomUUID());
    }

    BreakGlassNotificationDeliveryService(
            JdbcScheduledWorkRepository scheduledWork,
            AdministrativeBreakGlassRepository breakGlass,
            AdministrativeBreakGlassNotificationPublisher publisher,
            BreakGlassNotificationProperties properties,
            Clock clock,
            DoubleSupplier jitter,
            String leaseOwner) {
        this.scheduledWork = Objects.requireNonNull(scheduledWork, "scheduledWork");
        this.breakGlass = Objects.requireNonNull(breakGlass, "breakGlass");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.jitter = Objects.requireNonNull(jitter, "jitter");
        if (leaseOwner == null || leaseOwner.isBlank()) {
            throw new IllegalArgumentException("leaseOwner must not be blank");
        }
        this.leaseOwner = leaseOwner;
    }

    public DeliveryBatchResult deliverAvailable() {
        Instant now = clock.instant();
        List<ClaimedTenantWork> claimed = scheduledWork.claimDueByHandler(
                AdministrativeBreakGlassNotificationScheduler.HANDLER_TYPE,
                leaseOwner,
                now,
                properties.effectiveClaimLease(),
                properties.effectiveBatchSize());

        int completed = 0;
        int retrying = 0;
        int manualRequired = 0;

        for (ClaimedTenantWork item : claimed) {
            DeliveryOutcome outcome = deliverOne(item);
            switch (outcome) {
                case COMPLETED -> completed++;
                case RETRYING -> retrying++;
                case MANUAL_REQUIRED -> manualRequired++;
            }
        }

        return new DeliveryBatchResult(claimed.size(), completed, retrying, manualRequired);
    }

    private DeliveryOutcome deliverOne(ClaimedTenantWork item) {
        var work = item.work();
        UUID obligationId;
        try {
            obligationId = UUID.fromString(work.workKey());
        } catch (IllegalArgumentException malformed) {
            scheduledWork.markCompleted(item.tenant(), work.id(), leaseOwner, INVALID_WORK, clock.instant());
            return DeliveryOutcome.MANUAL_REQUIRED;
        }

        var obligation = breakGlass.findObligation(item.tenant(), obligationId).orElse(null);
        if (obligation == null) {
            scheduledWork.markCompleted(item.tenant(), work.id(), leaseOwner, INVALID_WORK, clock.instant());
            return DeliveryOutcome.MANUAL_REQUIRED;
        }
        if (obligation.type() != AdministrativeBreakGlassObligationType.SECURITY_NOTIFICATION) {
            scheduledWork.markCompleted(item.tenant(), work.id(), leaseOwner, INVALID_WORK, clock.instant());
            return DeliveryOutcome.MANUAL_REQUIRED;
        }
        if (obligation.state() != AdministrativeBreakGlassObligationState.PENDING) {
            scheduledWork.markCompleted(item.tenant(), work.id(), leaseOwner, clock.instant());
            return obligation.state() == AdministrativeBreakGlassObligationState.COMPLETED
                    ? DeliveryOutcome.COMPLETED
                    : DeliveryOutcome.MANUAL_REQUIRED;
        }

        var operation = breakGlass.find(item.tenant(), obligation.breakGlassOperationId()).orElse(null);
        if (operation == null) {
            breakGlass.markNotificationManualRequired(item.tenant(), obligationId, clock.instant());
            scheduledWork.markCompleted(
                    item.tenant(), work.id(), leaseOwner, OPERATION_MISSING, clock.instant());
            return DeliveryOutcome.MANUAL_REQUIRED;
        }

        if (work.subject() != null
                && (!"administrative-break-glass-operation".equals(work.subject().subjectType())
                || !operation.id().equals(work.subject().subjectId()))) {
            breakGlass.markNotificationManualRequired(item.tenant(), obligationId, clock.instant());
            scheduledWork.markCompleted(item.tenant(), work.id(), leaseOwner, INVALID_WORK, clock.instant());
            return DeliveryOutcome.MANUAL_REQUIRED;
        }

        var notification = new AdministrativeBreakGlassSecurityNotification(
                obligationId,
                item.tenant().tenantId(),
                operation.id(),
                operation.actorIdentityId(),
                operation.roleId(),
                operation.scope(),
                operation.incidentReference(),
                operation.activatedAt(),
                operation.validUntil(),
                operation.correlationId(),
                operation.causationId());

        try {
            publisher.publish(notification);
        } catch (AdministrativeBreakGlassNotificationDeliveryException failure) {
            return handleFailure(item, obligationId, failure.retryable(), failure.errorCode());
        } catch (RuntimeException failure) {
            return handleFailure(item, obligationId, true, UNKNOWN_FAILURE);
        }

        // Persistence/lease failures are not transport failures. Leave the technical lease to expire
        // so a replay can converge from the current Administration obligation state.
        breakGlass.completeNotificationObligation(item.tenant(), obligationId, clock.instant());
        scheduledWork.markCompleted(item.tenant(), work.id(), leaseOwner, clock.instant());
        return DeliveryOutcome.COMPLETED;
    }

    private DeliveryOutcome handleFailure(
            ClaimedTenantWork item,
            UUID obligationId,
            boolean retryable,
            String errorCode) {
        var work = item.work();
        Instant now = clock.instant();
        if (retryable && work.attemptCount() < properties.effectiveMaxAttempts()) {
            scheduledWork.reschedule(
                    item.tenant(),
                    work.id(),
                    leaseOwner,
                    now.plus(retryDelay(work.attemptCount())),
                    errorCode,
                    now);
            return DeliveryOutcome.RETRYING;
        }

        breakGlass.markNotificationManualRequired(item.tenant(), obligationId, now);
        scheduledWork.markCompleted(item.tenant(), work.id(), leaseOwner, errorCode, now);
        return DeliveryOutcome.MANUAL_REQUIRED;
    }

    private Duration retryDelay(int attemptCount) {
        long base = properties.effectiveRetryBaseDelay().toMillis();
        long max = properties.effectiveRetryMaxDelay().toMillis();
        long delay = base;
        for (int index = 1; index < attemptCount && delay < max; index++) {
            delay = delay > max / 2 ? max : delay * 2;
        }
        double random = jitter.getAsDouble();
        if (Double.isNaN(random) || random < 0.0 || random >= 1.0) {
            random = 0.5;
        }
        double factor = 0.5 + random;
        long jittered = Math.max(1L, Math.round(delay * factor));
        return Duration.ofMillis(Math.min(max, jittered));
    }

    private enum DeliveryOutcome {
        COMPLETED,
        RETRYING,
        MANUAL_REQUIRED
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
