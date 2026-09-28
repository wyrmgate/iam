package io.wyrmgate.iam.governance.application;

public final class ApprovalCommandException extends RuntimeException {
    private final String code;

    public ApprovalCommandException(String code, String message) {
        super(message);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        this.code = code;
    }

    public String code() {
        return code;
    }
}
