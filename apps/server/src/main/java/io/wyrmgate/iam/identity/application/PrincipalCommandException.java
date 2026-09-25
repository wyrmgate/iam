package io.wyrmgate.iam.identity.application;

/** Semantic Principal command failure for internal/application callers. */
public final class PrincipalCommandException extends RuntimeException {

    private final String code;

    public PrincipalCommandException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
