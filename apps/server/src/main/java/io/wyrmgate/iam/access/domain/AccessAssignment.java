package io.wyrmgate.iam.access.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Access-owned authoritative business access intent. */
public record AccessAssignment(
        UUID id,
        UUID identityId,
        TargetKind targetKind,
        UUID roleId,
        UUID entitlementId,
        PrincipalConstraintKind principalConstraintKind,
        UUID specificPrincipalId,
        ProvenanceKind provenanceKind,
        UUID provenanceRefId,
        LifecycleState lifecycleState,
        Instant validFrom,
        Instant validUntil,
        long revision,
        Instant createdAt,
        Instant updatedAt) {

    public AccessAssignment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(targetKind, "targetKind");
        Objects.requireNonNull(principalConstraintKind, "principalConstraintKind");
        Objects.requireNonNull(provenanceKind, "provenanceKind");
        Objects.requireNonNull(lifecycleState, "lifecycleState");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");

        if (targetKind == TargetKind.ROLE) {
            Objects.requireNonNull(roleId, "roleId");
            if (entitlementId != null) {
                throw new IllegalArgumentException("ROLE assignment must not carry entitlementId");
            }
        } else {
            Objects.requireNonNull(entitlementId, "entitlementId");
            if (roleId != null) {
                throw new IllegalArgumentException("ENTITLEMENT assignment must not carry roleId");
            }
        }

        if (principalConstraintKind == PrincipalConstraintKind.SPECIFIC) {
            Objects.requireNonNull(specificPrincipalId, "specificPrincipalId");
        } else if (specificPrincipalId != null) {
            throw new IllegalArgumentException("ANY principal constraint must not carry specificPrincipalId");
        }

        if (provenanceKind == ProvenanceKind.MANUAL && provenanceRefId != null) {
            throw new IllegalArgumentException("MANUAL provenance must not carry provenanceRefId");
        }

        if (validFrom != null && validUntil != null && !validUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException("validUntil must be after validFrom");
        }
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }

    public boolean isSemanticallyEffectiveAt(Instant at) {
        Objects.requireNonNull(at, "at");
        if (lifecycleState == LifecycleState.SUSPENDED
                || lifecycleState == LifecycleState.REVOKED
                || lifecycleState == LifecycleState.EXPIRED
                || lifecycleState == LifecycleState.CANCELLED) {
            return false;
        }
        if (validFrom != null && at.isBefore(validFrom)) {
            return false;
        }
        return validUntil == null || at.isBefore(validUntil);
    }

    public enum TargetKind {
        ROLE,
        ENTITLEMENT
    }

    public enum PrincipalConstraintKind {
        ANY,
        SPECIFIC
    }

    public enum ProvenanceKind {
        MANUAL
    }

    public enum LifecycleState {
        SCHEDULED,
        ACTIVE,
        SUSPENDED,
        REVOKED,
        EXPIRED,
        CANCELLED
    }
}
