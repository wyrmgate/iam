package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;

/** Consumer-owned semantic boundary for data-minimized break-glass AuditRecord evidence. */
public interface AdministrativeBreakGlassAuditSink {

    void record(
            TenantContext tenant,
            UUID actorIdentityId,
            String actionType,
            UUID resourceId,
            Outcome outcome,
            UUID correlationId,
            UUID causationId,
            Instant occurredAt);

    enum Outcome {
        SUCCESS,
        DENIED,
        FAILURE
    }
}
