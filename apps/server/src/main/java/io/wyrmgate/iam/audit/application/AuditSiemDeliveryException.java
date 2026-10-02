package io.wyrmgate.iam.audit.application;

public final class AuditSiemDeliveryException extends RuntimeException {

    private final boolean retryable;
    private final String errorCode;

    public AuditSiemDeliveryException(boolean retryable, String errorCode) {
        super(errorCode);
        this.retryable = retryable;
        this.errorCode = errorCode;
    }

    public AuditSiemDeliveryException(boolean retryable, String errorCode, Throwable cause) {
        super(errorCode, cause);
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
