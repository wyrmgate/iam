package io.wyrmgate.iam.audit.application;

import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassAuditSink;
import io.wyrmgate.iam.audit.domain.AuditOutcome;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Audit-owned adapter for data-minimized Administration break-glass evidence. */
public final class AuditAdministrativeBreakGlassAuditSink
        implements AdministrativeBreakGlassAuditSink {

    private final SecurityAuditPort audit;
    private final IdGenerator ids;

    public AuditAdministrativeBreakGlassAuditSink(
            SecurityAuditPort audit,
            IdGenerator ids) {
        this.audit = Objects.requireNonNull(audit, "audit");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    public void record(
            TenantContext tenant,
            UUID actorIdentityId,
            String actionType,
            UUID resourceId,
            Outcome outcome,
            UUID correlationId,
            UUID causationId,
            Instant occurredAt) {
        audit.append(
                tenant,
                new AuditRecordDraft(
                        ids.nextId(),
                        occurredAt,
                        actorIdentityId,
                        actionType,
                        "administrative-break-glass-operation",
                        resourceId,
                        switch (outcome) {
                            case SUCCESS -> AuditOutcome.SUCCESS;
                            case DENIED -> AuditOutcome.DENIED;
                            case FAILURE -> AuditOutcome.FAILURE;
                        },
                        correlationId,
                        causationId));
    }
}
