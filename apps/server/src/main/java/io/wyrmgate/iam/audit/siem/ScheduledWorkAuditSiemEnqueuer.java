package io.wyrmgate.iam.audit.siem;

import io.wyrmgate.iam.audit.application.AuditSiemEnqueuer;
import io.wyrmgate.iam.audit.domain.AuditRecord;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Platform scheduled-work adapter for the ADR-0037 post-commit SIEM handoff. */
public final class ScheduledWorkAuditSiemEnqueuer implements AuditSiemEnqueuer {

    public static final String HANDLER_TYPE = "audit.siem";

    private final JdbcScheduledWorkRepository scheduledWork;
    private final Clock clock;

    public ScheduledWorkAuditSiemEnqueuer(JdbcScheduledWorkRepository scheduledWork) {
        this(scheduledWork, Clock.systemUTC());
    }

    ScheduledWorkAuditSiemEnqueuer(
            JdbcScheduledWorkRepository scheduledWork,
            Clock clock) {
        this.scheduledWork = Objects.requireNonNull(scheduledWork, "scheduledWork");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void enqueue(TenantContext tenant, AuditRecord record) {
        Instant now = clock.instant();
        scheduledWork.enqueue(
                tenant,
                HANDLER_TYPE,
                record.id().toString(),
                null,
                now,
                now);
    }
}
