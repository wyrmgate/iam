package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;

/** Integration-facing governed Identity data needed for principal provisioning. */
public interface PrincipalProvisioningProfileQuery {

    Result resolve(TenantContext tenant, UUID identityId);

    enum Status {
        AVAILABLE,
        UNAVAILABLE
    }

    record Result(Status status, UUID identityId, String displayName) {
        public Result {
            Objects.requireNonNull(status, "status");
            if (status == Status.AVAILABLE) {
                Objects.requireNonNull(identityId, "identityId");
                if (displayName == null || displayName.isBlank()) {
                    throw new IllegalArgumentException("displayName must not be blank");
                }
            } else if (identityId != null || displayName != null) {
                throw new IllegalArgumentException("UNAVAILABLE must not carry profile data");
            }
        }

        public static Result available(UUID identityId, String displayName) {
            return new Result(Status.AVAILABLE, identityId, displayName);
        }

        public static Result unavailable() {
            return new Result(Status.UNAVAILABLE, null, null);
        }
    }
}
