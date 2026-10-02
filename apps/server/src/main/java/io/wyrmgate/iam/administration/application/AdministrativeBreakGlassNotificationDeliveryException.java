package io.wyrmgate.iam.administration.application;

/** Normalized security-notification transport failure without remote response bodies. */
public final class AdministrativeBreakGlassNotificationDeliveryException extends RuntimeException {
    private final boolean retryable;
    private final String errorCode;

    public AdministrativeBreakGlassNotificationDeliveryException(
            boolean retryable, String errorCode) {
        this(retryable, errorCode, null);
    }

    public AdministrativeBreakGlassNotificationDeliveryException(
            boolean retryable, String errorCode, Throwable cause) {
        super(errorCode, cause);
        if (errorCode == null || errorCode.isBlank() || errorCode.length() > 128) {
            throw new IllegalArgumentException("errorCode must contain between 1 and 128 characters");
        }
        this.retryable = retryable;
        this.errorCode = errorCode;
    }

    public boolean retryable() { return retryable; }

    public String errorCode() { return errorCode; }
}
