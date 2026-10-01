package io.wyrmgate.iam.administration.application;

/** Stable semantic failure for Administration authority-management commands. */
public final class AdministrativeAuthorityException extends RuntimeException {

    private final String code;

    public AdministrativeAuthorityException(String code, String message) {
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
