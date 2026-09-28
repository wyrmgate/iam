package io.wyrmgate.iam.governance.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RequestItem(
        UUID id,
        UUID accessRequestId,
        TargetKind targetKind,
        UUID roleId,
        UUID entitlementId,
        PrincipalConstraintKind principalConstraintKind,
        UUID specificPrincipalId,
        Instant validFrom,
        Instant validUntil,
        LifecycleState lifecycleState,
        UUID approvalPlanId,
        UUID accessAssignmentId,
        String denialCode,
        long revision,
        Instant createdAt,
        Instant updatedAt) {

    public RequestItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(accessRequestId, "accessRequestId");
        Objects.requireNonNull(targetKind, "targetKind");
        Objects.requireNonNull(principalConstraintKind, "principalConstraintKind");
        Objects.requireNonNull(lifecycleState, "lifecycleState");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (targetKind == TargetKind.ROLE) {
            Objects.requireNonNull(roleId, "roleId");
            if (entitlementId != null) throw new IllegalArgumentException("ROLE item must not carry entitlementId");
        } else {
            Objects.requireNonNull(entitlementId, "entitlementId");
            if (roleId != null) throw new IllegalArgumentException("ENTITLEMENT item must not carry roleId");
        }
        if (principalConstraintKind == PrincipalConstraintKind.SPECIFIC) {
            Objects.requireNonNull(specificPrincipalId, "specificPrincipalId");
        } else if (specificPrincipalId != null) {
            throw new IllegalArgumentException("ANY item must not carry specificPrincipalId");
        }
        if (validFrom != null && validUntil != null && !validUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException("validUntil must be after validFrom");
        }
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
    }

    public enum TargetKind {
        ROLE,
        ENTITLEMENT
    }

    public enum PrincipalConstraintKind {
        ANY,
        SPECIFIC
    }

    public enum LifecycleState {
        DRAFT,
        SUBMITTED,
        EVALUATING,
        PENDING_APPROVAL,
        AUTHORIZED,
        APPLIED,
        DENIED,
        REJECTED,
        CANCELLED,
        EXPIRED
    }
}
