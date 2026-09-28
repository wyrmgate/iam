package io.wyrmgate.iam.governance.application;

public final class AccessRequestCommandException extends RuntimeException {
    private final String code;

    public AccessRequestCommandException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
