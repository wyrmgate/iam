package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public interface DesiredStateProjectionRepository {

    void replacePrincipal(TenantContext tenant, DesiredPrincipalState state);

    void replaceGrant(TenantContext tenant, DesiredGrantState state);

    DesiredGrantState reconcileGrant(
            TenantContext tenant,
            UUID identityId,
            UUID applicationTargetId,
            UUID entitlementId,
            String principalConstraintKey,
            UUID principalId,
            DesiredPresence desiredState,
            Instant computedAt);

    DesiredPrincipalState reconcilePrincipal(
            TenantContext tenant,
            UUID identityId,
            UUID applicationTargetId,
            DesiredPresence desiredState,
            Instant computedAt);

    boolean hasPresentGrant(
            TenantContext tenant,
            UUID identityId,
            UUID applicationTargetId);

    java.util.List<DesiredGrantState> findPresentAnyGrants(
            TenantContext tenant,
            UUID identityId,
            UUID applicationTargetId);

    /**
     * Bounded repair candidates for one Principal change. SPECIFIC rows are always
     * reconsidered; ANY rows are reconsidered only for the Principal's current or
     * previous Identity on the same target.
     */
    default java.util.List<DesiredGrantState> findPrincipalRefreshPage(
            TenantContext tenant,
            UUID principalId,
            UUID applicationTargetId,
            UUID currentIdentityId,
            UUID previousIdentityId,
            UUID afterId,
            int limit) {
        throw new UnsupportedOperationException(
                "Principal desired-state refresh paging is not implemented");
    }

    default boolean hasPresentTechnicalGrant(
            TenantContext tenant,
            UUID applicationTargetId,
            UUID entitlementId,
            UUID principalId) {
        throw new UnsupportedOperationException(
                "technical grant demand query is not implemented");
    }

    java.util.Optional<DesiredGrantState> findGrantTuple(
            TenantContext tenant,
            UUID identityId,
            UUID entitlementId,
            String principalConstraintKey);

    java.util.Optional<DesiredGrantState> findGrantById(
            TenantContext tenant, UUID desiredGrantId);

    default java.util.Optional<DesiredPrincipalState> findPrincipalTuple(
            TenantContext tenant, UUID identityId, UUID applicationTargetId) {
        return java.util.Optional.empty();
    }

    default java.util.Optional<DesiredPrincipalState> findPrincipalById(
            TenantContext tenant, UUID desiredPrincipalId) {
        return java.util.Optional.empty();
    }

    DesiredAccessStateQuery.Freshness principalFreshness(TenantContext tenant, UUID id);

    DesiredAccessStateQuery.Freshness grantFreshness(TenantContext tenant, UUID id);

    record DesiredPrincipalState(
            UUID id,
            UUID identityId,
            UUID applicationTargetId,
            DesiredPresence desiredState,
            long desiredRevision,
            long sourceGeneration,
            Instant computedAt) {
        public DesiredPrincipalState {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(identityId, "identityId");
            Objects.requireNonNull(applicationTargetId, "applicationTargetId");
            Objects.requireNonNull(desiredState, "desiredState");
            Objects.requireNonNull(computedAt, "computedAt");
            if (desiredRevision < 1 || sourceGeneration < 1) {
                throw new IllegalArgumentException("desiredRevision/sourceGeneration must be positive");
            }
        }
    }

    record DesiredGrantState(
            UUID id,
            UUID identityId,
            UUID applicationTargetId,
            UUID entitlementId,
            String principalConstraintKey,
            UUID principalId,
            DesiredPresence desiredState,
            long desiredRevision,
            long sourceGeneration,
            Instant computedAt) {
        public DesiredGrantState {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(identityId, "identityId");
            Objects.requireNonNull(applicationTargetId, "applicationTargetId");
            Objects.requireNonNull(entitlementId, "entitlementId");
            if (principalConstraintKey == null || principalConstraintKey.isBlank()) {
                throw new IllegalArgumentException("principalConstraintKey must not be blank");
            }
            Objects.requireNonNull(desiredState, "desiredState");
            Objects.requireNonNull(computedAt, "computedAt");
            if (desiredRevision < 1 || sourceGeneration < 1) {
                throw new IllegalArgumentException("desiredRevision/sourceGeneration must be positive");
            }
        }
    }

    enum DesiredPresence {
        PRESENT,
        ABSENT
    }
}
