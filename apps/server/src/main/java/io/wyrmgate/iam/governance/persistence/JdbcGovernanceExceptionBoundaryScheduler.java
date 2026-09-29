package io.wyrmgate.iam.governance.persistence;

import io.wyrmgate.iam.governance.application.GovernanceExceptionBoundaryScheduler;
import io.wyrmgate.iam.governance.domain.GovernanceExceptionModels.GovernanceException;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository.SubjectReference;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;

public final class JdbcGovernanceExceptionBoundaryScheduler
        implements GovernanceExceptionBoundaryScheduler {

    private final JdbcScheduledWorkRepository scheduledWork;

    public JdbcGovernanceExceptionBoundaryScheduler(
            JdbcScheduledWorkRepository scheduledWork) {
        this.scheduledWork = Objects.requireNonNull(
                scheduledWork, "scheduledWork");
    }

    @Override
    public void scheduleExpiry(
            TenantContext tenant,
            GovernanceException exception,
            Instant now) {
        if (!exception.validUntil().isAfter(now)) {
            return;
        }
        scheduledWork.enqueue(
                tenant,
                HANDLER_TYPE,
                exception.id() + ":valid-until",
                new SubjectReference(
                        "governance-exception",
                        exception.id(),
                        exception.revision()),
                exception.validUntil(),
                now);
    }
}
