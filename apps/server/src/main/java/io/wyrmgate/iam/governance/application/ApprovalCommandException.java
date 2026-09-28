package io.wyrmgate.iam.governance.application;

public final class ApprovalCommandException extends RuntimeException {

    private final String code;

    public ApprovalCommandException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
