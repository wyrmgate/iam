package io.wyrmgate.iam.authentication.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;

/** Authentication-facing semantic query for current governed Principal/Identity eligibility. */
public interface AuthenticationSubjectQuery {

    Result resolve(TenantContext tenant, UUID principalId);

    enum Status {
        ELIGIBLE,
        INELIGIBLE,
        UNAVAILABLE
    }

    record Result(Status status, UUID principalId, UUID identityId) {
        public Result {
            Objects.requireNonNull(status, "status");
            if (status == Status.ELIGIBLE || status == Status.INELIGIBLE) {
                Objects.requireNonNull(principalId, "principalId");
                Objects.requireNonNull(identityId, "identityId");
            } else if (principalId != null || identityId != null) {
                throw new IllegalArgumentException("UNAVAILABLE must not carry governed subject context");
            }
        }

        public static Result eligible(UUID principalId, UUID identityId) {
            return new Result(Status.ELIGIBLE, principalId, identityId);
        }

        public static Result ineligible(UUID principalId, UUID identityId) {
            return new Result(Status.INELIGIBLE, principalId, identityId);
        }

        public static Result unavailable() {
            return new Result(Status.UNAVAILABLE, null, null);
        }
    }
}