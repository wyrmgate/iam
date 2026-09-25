package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;

/** Consumer-facing Identity query for target/native-key Principal resolution. */
public interface PrincipalResolutionQuery {

    Resolution resolve(
            TenantContext tenant,
            UUID applicationTargetId,
            String nativePrincipalKey);

    enum Status {
        NOT_FOUND,
        UNCORRELATED,
        RESOLVED
    }

    record Resolution(Status status, UUID principalId, UUID identityId) {
        public Resolution {
            Objects.requireNonNull(status, "status");
            if (status == Status.NOT_FOUND) {
                if (principalId != null || identityId != null) {
                    throw new IllegalArgumentException("NOT_FOUND must not carry IDs");
                }
            } else if (status == Status.UNCORRELATED) {
                Objects.requireNonNull(principalId, "principalId");
                if (identityId != null) {
                    throw new IllegalArgumentException("UNCORRELATED must not carry identityId");
                }
            } else {
                Objects.requireNonNull(principalId, "principalId");
                Objects.requireNonNull(identityId, "identityId");
            }
        }

        public static Resolution notFound() {
            return new Resolution(Status.NOT_FOUND, null, null);
        }

        public static Resolution uncorrelated(UUID principalId) {
            return new Resolution(Status.UNCORRELATED, principalId, null);
        }

        public static Resolution resolved(UUID principalId, UUID identityId) {
            return new Resolution(Status.RESOLVED, principalId, identityId);
        }
    }
}
