package io.wyrmgate.iam.administration.application;

/** Fail-closed rejection for the explicit ADR-0049 legacy Platform Owner adoption operation. */
public final class PlatformOwnerAdoptionNotEligibleException extends RuntimeException {

    public PlatformOwnerAdoptionNotEligibleException(String reason) {
        super("platform owner adoption is not eligible: " + reason);
    }
}
