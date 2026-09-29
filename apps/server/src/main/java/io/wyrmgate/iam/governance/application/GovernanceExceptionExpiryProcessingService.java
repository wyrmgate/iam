package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class GovernanceExceptionExpiryProcessingService {

    private static final Duration LEASE =
            Duration.ofSeconds(30);
    private static final int BATCH_SIZE = 50;

    private final JdbcScheduledWorkRepository scheduledWork;
    private final GovernanceExceptionService exceptions;
    private final Clock clock;
    private final String leaseOwner;

    public GovernanceExceptionExpiryProcessingService(
            JdbcScheduledWorkRepository scheduledWork,
            GovernanceExceptionService exceptions) {
        this(
                scheduledWork,
                exceptions,
                Clock.systemUTC(),
                "governance-exception-expiry-"
                        + UUID.randomUUID());
    }

    GovernanceExceptionExpiryProcessingService(
            JdbcScheduledWorkRepository scheduledWork,
            GovernanceExceptionService exceptions,
            Clock clock,
            String leaseOwner) {
        this.scheduledWork = Objects.requireNonNull(
                scheduledWork, "scheduledWork");
        this.exceptions = Objects.requireNonNull(
                exceptions, "exceptions");
        this.clock = Objects.requireNonNull(
                clock, "clock");
        this.leaseOwner = Objects.requireNonNull(
                leaseOwner, "leaseOwner");
    }

    public ProcessingResult processAvailable() {
        Instant now = clock.instant();
        var claimed = scheduledWork.claimDueByHandler(
                GovernanceExceptionBoundaryScheduler
                        .HANDLER_TYPE,
                leaseOwner,
                now,
                LEASE,
                BATCH_SIZE);
        int completed = 0;
        int failed = 0;
        for (var item : claimed) {
            try {
                var subject = item.work().subject();
                if (subject == null
                        || !"governance-exception"
                                .equals(subject.subjectType())) {
                    throw new IllegalArgumentException(
                            "invalid GovernanceException scheduled work subject");
                }
                exceptions.expireIfDue(
                        item.tenant(),
                        subject.subjectId(),
                        now);
                scheduledWork.markCompleted(
                        item.tenant(),
                        item.work().id(),
                        leaseOwner,
                        now);
                completed++;
            } catch (RuntimeException retryable) {
                failed++;
            }
        }
        return new ProcessingResult(
                claimed.size(),
                completed,
                failed);
    }

    public record ProcessingResult(
            int claimed,
            int completed,
            int failed) {}
}
