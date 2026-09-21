package io.wyrmgate.iam.integration.event;

/** Normalized technical delivery failure classification for external event adapters. */
public final class IntegrationEventDeliveryException extends RuntimeException {

    private final boolean retryable;
    private final String errorCode;

    public IntegrationEventDeliveryException(boolean retryable, String errorCode) {
        super(errorCode);
        if (errorCode == null || errorCode.isBlank() || errorCode.length() > 128) {
            throw new IllegalArgumentException("errorCode must contain between 1 and 128 characters");
        }
        this.retryable = retryable;
        this.errorCode = errorCode;
    }

    public IntegrationEventDeliveryException(boolean retryable, String errorCode, Throwable cause) {
        super(errorCode, cause);
        if (errorCode == null || errorCode.isBlank() || errorCode.length() > 128) {
            throw new IllegalArgumentException("errorCode must contain between 1 and 128 characters");
        }
        this.retryable = retryable;
        this.errorCode = errorCode;
    }

    public boolean retryable() {
        return retryable;
    }

    public String errorCode() {
        return errorCode;
    }
}
