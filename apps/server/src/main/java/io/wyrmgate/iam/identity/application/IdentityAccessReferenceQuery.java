package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;

/** Identity-owned semantic references used by Access without exposing Identity persistence. */
public interface IdentityAccessReferenceQuery {

    boolean identityExists(TenantContext tenant, UUID identityId);

    PrincipalReference principal(TenantContext tenant, UUID principalId);

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
