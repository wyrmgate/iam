package io.wyrmgate.iam.integration.application;

public final class IntegrationAdministrationException extends RuntimeException {

    private final String code;

    public IntegrationAdministrationException(String code, String message) {
        super(message);
        if (code == null || code.isBlank()) throw new IllegalArgumentException("code must not be blank");
        this.code = code;
    }

    public String code() {
        return code;
    }
}
