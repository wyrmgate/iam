package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public interface DesiredStateProjectionRepository {

    void replacePrincipal(TenantContext tenant, DesiredPrincipalState state);

    void replaceGrant(TenantContext tenant, DesiredGrantState state);

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
