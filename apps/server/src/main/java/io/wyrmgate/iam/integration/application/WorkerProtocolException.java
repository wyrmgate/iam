package io.wyrmgate.iam.integration.application;

public final class WorkerProtocolException extends RuntimeException {
    private final String code;

    public WorkerProtocolException(String code, String message) {
        super(message);
        if (code == null || code.isBlank()) throw new IllegalArgumentException("code must not be blank");
        this.code = code;
    }

    public String code() {
        return code;
    }
}
