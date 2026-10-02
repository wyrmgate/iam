package io.wyrmgate.iam.administration.persistence;

import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassNotificationScheduler;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository.SubjectReference;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

final class JdbcAdministrativeBreakGlassNotificationScheduler
        implements AdministrativeBreakGlassNotificationScheduler {

    static final String HANDLER_TYPE = "administration.break-glass.security-notification";

    private final JdbcScheduledWorkRepository work;

    JdbcAdministrativeBreakGlassNotificationScheduler(JdbcScheduledWorkRepository work) {
        this.work = Objects.requireNonNull(work, "work");
    }

    @Override
    public void schedule(
            TenantContext tenant,
            UUID obligationId,
            UUID operationId,
            long operationRevision,
            Instant now) {
        work.enqueue(
                tenant,
                HANDLER_TYPE,
                obligationId.toString(),
                new SubjectReference("administrative-break-glass-operation", operationId, operationRevision),
                now,
                now);
    }
}
