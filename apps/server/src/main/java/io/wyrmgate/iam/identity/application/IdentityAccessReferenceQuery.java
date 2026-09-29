package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;

/** Identity-owned semantic references used by Access without exposing Identity persistence. */
public interface IdentityAccessReferenceQuery {

    boolean identityExists(TenantContext tenant, UUID identityId);

    default IdentityAccessStatus accessStatus(
            TenantContext tenant,
            UUID identityId) {
        return identityExists(tenant, identityId)
                ? IdentityAccessStatus.eligible(
                        "ACTIVE", 0)
                : IdentityAccessStatus.notFound();
    }

    PrincipalReference principal(TenantContext tenant, UUID principalId);

    PrincipalSelection selectUniqueActivePrincipal(
            TenantContext tenant, UUID identityId, UUID applicationTargetId);

    record IdentityAccessStatus(
            AccessStatus status,
            String lifecycleState,
            long revision) {
        public IdentityAccessStatus {
            Objects.requireNonNull(status, "status");
            if (status == AccessStatus.NOT_FOUND) {
                if (lifecycleState != null || revision != 0) {
                    throw new IllegalArgumentException(
                            "NOT_FOUND access status must not carry lifecycle/revision");
                }
            } else {
                if (lifecycleState == null || lifecycleState.isBlank()) {
                    throw new IllegalArgumentException(
                            "identity access status requires lifecycleState");
                }
                if (revision < 0) {
                    throw new IllegalArgumentException(
                            "revision must not be negative");
                }
            }
        }

        public static IdentityAccessStatus notFound() {
            return new IdentityAccessStatus(
                    AccessStatus.NOT_FOUND, null, 0);
        }

        public static IdentityAccessStatus eligible(
                String lifecycleState,
                long revision) {
            return new IdentityAccessStatus(
                    AccessStatus.ACCESS_ELIGIBLE,
                    lifecycleState,
                    revision);
        }

        public static IdentityAccessStatus ineligible(
                String lifecycleState,
                long revision) {
            return new IdentityAccessStatus(
                    AccessStatus.ACCESS_INELIGIBLE,
                    lifecycleState,
                    revision);
        }
    }

    enum AccessStatus {
        NOT_FOUND,
        ACCESS_ELIGIBLE,
        ACCESS_INELIGIBLE
    }

    record PrincipalSelection(
            PrincipalSelectionStatus status,
            UUID principalId) {
        public PrincipalSelection {
            Objects.requireNonNull(status, "status");
            if (status == PrincipalSelectionStatus.RESOLVED) {
                Objects.requireNonNull(principalId, "principalId");
            } else if (principalId != null) {
                throw new IllegalArgumentException(
                        status + " principal selection must not carry principalId");
            }
        }

        public static PrincipalSelection none() {
            return new PrincipalSelection(PrincipalSelectionStatus.NONE, null);
        }

        public static PrincipalSelection ambiguous() {
            return new PrincipalSelection(PrincipalSelectionStatus.AMBIGUOUS, null);
        }

        public static PrincipalSelection resolved(UUID principalId) {
            return new PrincipalSelection(
                    PrincipalSelectionStatus.RESOLVED, principalId);
        }
    }

    enum PrincipalSelectionStatus {
        NONE,
        AMBIGUOUS,
        RESOLVED
    }

    record PrincipalReference(
            Status status,
            UUID identityId,
            UUID applicationTargetId) {
        public PrincipalReference {
            Objects.requireNonNull(status, "status");
            if (status == Status.NOT_FOUND) {
                if (identityId != null || applicationTargetId != null) {
                    throw new IllegalArgumentException("NOT_FOUND must not carry principal context");
                }
            } else {
                Objects.requireNonNull(applicationTargetId, "applicationTargetId");
                if (status == Status.UNCORRELATED && identityId != null) {
                    throw new IllegalArgumentException("UNCORRELATED must not carry identityId");
                }
                if (status == Status.RESOLVED) {
                    Objects.requireNonNull(identityId, "identityId");
                }
            }
        }

        public static PrincipalReference notFound() {
            return new PrincipalReference(Status.NOT_FOUND, null, null);
        }

        public static PrincipalReference uncorrelated(UUID applicationTargetId) {
            return new PrincipalReference(Status.UNCORRELATED, null, applicationTargetId);
        }

        public static PrincipalReference resolved(UUID identityId, UUID applicationTargetId) {
            return new PrincipalReference(Status.RESOLVED, identityId, applicationTargetId);
        }
    }

    enum Status {
        NOT_FOUND,
        UNCORRELATED,
        RESOLVED
    }
}
