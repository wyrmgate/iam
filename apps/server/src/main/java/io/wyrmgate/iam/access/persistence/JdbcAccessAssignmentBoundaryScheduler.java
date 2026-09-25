package io.wyrmgate.iam.access.persistence;

import io.wyrmgate.iam.access.application.AccessAssignmentBoundaryScheduler;
import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository.SubjectReference;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;

public final class JdbcAccessAssignmentBoundaryScheduler
        implements AccessAssignmentBoundaryScheduler {

    private final JdbcScheduledWorkRepository scheduledWork;

    public JdbcAccessAssignmentBoundaryScheduler(
            JdbcScheduledWorkRepository scheduledWork) {
        this.scheduledWork = Objects.requireNonNull(
                scheduledWork, "scheduledWork");
    }

    @Override
    public void scheduleBoundaries(
            TenantContext tenant,
            AccessAssignment assignment,
            Instant now) {
        SubjectReference subject = new SubjectReference(
                "access-assignment",
                assignment.id(),
                assignment.revision());

        if (assignment.validFrom() != null
                && assignment.validFrom().isAfter(now)) {
            scheduledWork.enqueue(
                    tenant,
                    HANDLER_TYPE,
                    assignment.id() + ":valid-from",
                    subject,
                    assignment.validFrom(),
                    now);
        }
        if (assignment.validUntil() != null
                && assignment.validUntil().isAfter(now)) {
            scheduledWork.enqueue(
                    tenant,
                    HANDLER_TYPE,
                    assignment.id() + ":valid-until",
                    subject,
                    assignment.validUntil(),
                    now);
        }
    }
}
