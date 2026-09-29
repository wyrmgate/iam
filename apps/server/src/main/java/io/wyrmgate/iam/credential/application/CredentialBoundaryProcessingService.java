package io.wyrmgate.iam.credential.application;

import io.wyrmgate.iam.credential.persistence.JdbcCredentialBoundaryScheduler;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class CredentialBoundaryProcessingService {

    private static final Duration LEASE =
            Duration.ofSeconds(30);
    private static final int BATCH_SIZE = 100;
    private static final String LEASE_OWNER =
            "credential-boundary";

    private final JdbcScheduledWorkRepository scheduledWork;
    private final CredentialService credentials;
    private final Clock clock;

    public CredentialBoundaryProcessingService(
            JdbcScheduledWorkRepository scheduledWork,
            CredentialService credentials) {
        this(
                scheduledWork,
                credentials,
                Clock.systemUTC());
    }

    CredentialBoundaryProcessingService(
            JdbcScheduledWorkRepository scheduledWork,
            CredentialService credentials,
            Clock clock) {
        this.scheduledWork = Objects.requireNonNull(
                scheduledWork, "scheduledWork");
        this.credentials = Objects.requireNonNull(
                credentials, "credentials");
        this.clock = Objects.requireNonNull(
                clock, "clock");
    }

    public int processDue() {
        Instant now = clock.instant();
        var claimed =
                scheduledWork.claimDueByHandler(
                        JdbcCredentialBoundaryScheduler
                                .HANDLER_TYPE,
                        LEASE_OWNER,
                        now,
                        LEASE,
                        BATCH_SIZE);
        int processed = 0;
        for (var item : claimed) {
            var subject = item.work().subject();
            if (subject == null
                    || !"credential".equals(
                            subject.subjectType())) {
                throw new IllegalStateException(
                        "invalid Credential boundary work");
            }
            credentials.materializeBoundary(
                    item.tenant(),
                    subject.subjectId(),
                    now);
            scheduledWork.markCompleted(
                    item.tenant(),
                    item.work().id(),
                    LEASE_OWNER,
                    clock.instant());
            processed++;
        }
        return processed;
    }
}
