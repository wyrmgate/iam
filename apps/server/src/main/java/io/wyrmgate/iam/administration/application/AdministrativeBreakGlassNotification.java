package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.administration.domain.AdministrativeScopeType;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Closed data-minimized security notification defined by ADR-0033. */
public record AdministrativeBreakGlassNotification(
        String version,
        String type,
        UUID tenantId,
        UUID obligationId,
        UUID breakGlassOperationId,
        UUID actorIdentityId,
        UUID roleId,
        AdministrativeScopeType scopeType,
        String scopeResourceType,
        UUID scopeResourceId,
        String scopeKey,
        String incidentReference,
        Instant activatedAt,
        Instant validUntil,
        UUID correlationId,
        UUID causationId) {

    public AdministrativeBreakGlassNotification {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(obligationId, "obligationId");
        Objects.requireNonNull(breakGlassOperationId, "breakGlassOperationId");
        Objects.requireNonNull(actorIdentityId, "actorIdentityId");
        Objects.requireNonNull(roleId, "roleId");
        Objects.requireNonNull(scopeType, "scopeType");
        Objects.requireNonNull(incidentReference, "incidentReference");
        Objects.requireNonNull(activatedAt, "activatedAt");
        Objects.requireNonNull(validUntil, "validUntil");
    }

    public static AdministrativeBreakGlassNotification from(
            AdministrativeBreakGlassNotificationWork work) {
        var operation = work.operation();
        var scope = operation.scope();
        return new AdministrativeBreakGlassNotification(
                "1",
                "iam.administration.break-glass.security-notification.v1",
                work.tenant().tenantId(),
                work.obligationId(),
                operation.id(),
                operation.actorIdentityId(),
                operation.roleId(),
                scope.type(),
                scope.resourceType(),
                scope.resourceId(),
                scope.scopeKey(),
                operation.incidentReference(),
                operation.activatedAt(),
                operation.validUntil(),
                operation.correlationId(),
                operation.causationId());
    }
}
