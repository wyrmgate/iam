package io.wyrmgate.iam.administration.application;

import java.util.Objects;
import java.util.UUID;

/** Outcome of the narrowly scoped ADR-0047 sealed-installation compatibility operation. */
public record StandaloneTenantOwnerRecoveryResult(Status status, UUID administrativeRoleId) {

    public StandaloneTenantOwnerRecoveryResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(administrativeRoleId, "administrativeRoleId");
    }

    public enum Status {
        RECOVERED,
        ALREADY_COMPLETE
    }
}
