package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.administration.domain.AdministrativeScope;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Closed data-minimized payload model for ADR-0033 security notification delivery. */
public record AdministrativeBreakGlassSecurityNotification(
        UUID notificationId,
        UUID tenantId,
        UUID breakGlassOperationId,
        UUID actorIdentityId,
        UUID roleId,
        AdministrativeScope scope,
        String incidentReference,
        Instant activatedAt,
        Instant validUntil,
        UUID correlationId,
        UUID causationId) {

    public static final String TYPE = "iam.administration.break-glass.security-notification.v1";

    public AdministrativeBreakGlassSecurityNotification {
        Objects.requireNonNull(notificationId, "notificationId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(breakGlassOperationId, "breakGlassOperationId");
        Objects.requireNonNull(actorIdentityId, "actorIdentityId");
        Objects.requireNonNull(roleId, "roleId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(incidentReference, "incidentReference");
        Objects.requireNonNull(activatedAt, "activatedAt");
        Objects.requireNonNull(validUntil, "validUntil");
        if (incidentReference.isBlank()) {
            throw new IllegalArgumentException("incidentReference must not be blank");
        }
    }
}
