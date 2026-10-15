package io.wyrmgate.iam.administration.application;

/** Raised when ADR-0047 cannot prove the exact supported bootstrap state. */
public final class StandaloneTenantOwnerRecoveryNotEligibleException extends RuntimeException {

    private final String reason;

    public StandaloneTenantOwnerRecoveryNotEligibleException(String reason) {
        super("Standalone tenant-owner recovery is not eligible: " + reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
