package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Consumer-facing current desired grant snapshot for Integration planning. */
public interface DesiredProvisioningStateQuery {

    Result desiredGrant(TenantContext tenant, UUID desiredGrantId);

    enum Status {
        CURRENT,
        ABSENT,
        UNAVAILABLE
    }

    record Result(
            Status status,
            DesiredStateProjectionRepository.DesiredGrantState grant) {
        public Result {
            Objects.requireNonNull(status, "status");
            if (status == Status.CURRENT) {
                Objects.requireNonNull(grant, "grant");
            } else if (grant != null) {
                throw new IllegalArgumentException(
                        status + " desired grant result must not carry state");
            }
        }

        public static Result current(
                DesiredStateProjectionRepository.DesiredGrantState grant) {
            return new Result(Status.CURRENT, grant);
        }

        public static Result absent() {
            return new Result(Status.ABSENT, null);
        }

        public static Result unavailable() {
            return new Result(Status.UNAVAILABLE, null);
        }
    }
}
