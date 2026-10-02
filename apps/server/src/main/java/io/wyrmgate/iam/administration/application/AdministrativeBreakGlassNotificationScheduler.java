package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;

/** Queues technical delivery for an Administration-owned SECURITY_NOTIFICATION obligation. */
public interface AdministrativeBreakGlassNotificationScheduler {
    String HANDLER_TYPE = "administration.break-glass.security-notification";

    void schedule(
            TenantContext tenant,
            UUID obligationId,
            UUID operationId,
            long operationRevision,
            Instant now);
}
