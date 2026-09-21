package io.wyrmgate.iam.integration.provider.scim;

import java.util.Objects;

public final class ScimProviderException extends RuntimeException {

    public enum FailureCategory {
        TRANSIENT,
        RATE_LIMITED,
        AUTHENTICATION,
        AUTHORIZATION,
        VALIDATION,
        UNSUPPORTED,
        PROVIDER
    }

    private final FailureCategory category;
    private final String providerErrorCode;
    private final String providerRequestId;
    private final Integer retryAfterSeconds;
    private final int httpStatus;

    public ScimProviderException(
            FailureCategory category,
            String providerErrorCode,
            String providerRequestId,
            Integer retryAfterSeconds,
            int httpStatus,
            String message) {
        super(message);
        this.category = Objects.requireNonNull(category, "category");
        this.providerErrorCode = providerErrorCode;
        this.providerRequestId = providerRequestId;
        this.retryAfterSeconds = retryAfterSeconds;
        this.httpStatus = httpStatus;
    }

    public FailureCategory category() {
        return category;
    }

    public String providerErrorCode() {
        return providerErrorCode;
    }

    public String providerRequestId() {
        return providerRequestId;
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
