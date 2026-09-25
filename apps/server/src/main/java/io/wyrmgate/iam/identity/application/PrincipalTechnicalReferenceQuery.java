package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Integration-facing technical Principal reference without exposing Identity persistence. */
public interface PrincipalTechnicalReferenceQuery {

    Result resolve(TenantContext tenant, UUID principalId);

    List<Result> activeForIdentityTarget(
            TenantContext tenant, UUID identityId, UUID applicationTargetId);

    enum Status {
        ACTIVE,
        UNAVAILABLE
    }

    record Result(
            Status status,
            UUID principalId,
            UUID identityId,
            UUID applicationTargetId,
            String nativePrincipalKey) {
        public Result {
            Objects.requireNonNull(status, "status");
            if (status == Status.ACTIVE) {
                Objects.requireNonNull(principalId, "principalId");
                Objects.requireNonNull(identityId, "identityId");
                Objects.requireNonNull(applicationTargetId, "applicationTargetId");
                if (nativePrincipalKey == null || nativePrincipalKey.isBlank()) {
                    throw new IllegalArgumentException(
                            "nativePrincipalKey must not be blank");
                }
            } else if (principalId != null || identityId != null
                    || applicationTargetId != null || nativePrincipalKey != null) {
                throw new IllegalArgumentException(
                        "UNAVAILABLE must not carry Principal context");
            }
        }

        public static Result active(
                UUID principalId,
                UUID identityId,
                UUID applicationTargetId,
                String nativePrincipalKey) {
            return new Result(
                    Status.ACTIVE,
                    principalId,
                    identityId,
                    applicationTargetId,
                    nativePrincipalKey);
        }

        public static Result unavailable() {
            return new Result(Status.UNAVAILABLE, null, null, null, null);
        }
    }
}
