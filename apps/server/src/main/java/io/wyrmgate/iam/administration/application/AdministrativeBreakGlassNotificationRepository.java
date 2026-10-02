package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Administration-owned persistence boundary for durable SECURITY_NOTIFICATION delivery. */
public interface AdministrativeBreakGlassNotificationRepository {

    List<AdministrativeBreakGlassNotificationWork> claimPending(
            Instant now, Duration leaseDuration, int limit);

    void markCompleted(
            TenantContext tenant, UUID obligationId, int expectedAttemptCount, Instant now);

    void markRetry(
            TenantContext tenant,
            UUID obligationId,
            int expectedAttemptCount,
            Instant nextAttemptAt,
            String errorCode,
            Instant now);

    void markManualRequired(
            TenantContext tenant,
            UUID obligationId,
            int expectedAttemptCount,
            String errorCode,
            Instant now);
}
