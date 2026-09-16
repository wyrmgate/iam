package io.wyrmgate.iam.api.identity;

import io.wyrmgate.iam.api.identity.IdentityApiModels.FieldError;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;

final class IdentityApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final UUID correlationId;
    private final List<FieldError> fieldErrors;

    IdentityApiException(
            HttpStatus status,
            String code,
            String message,
            UUID correlationId,
            List<FieldError> fieldErrors) {
        super(message);
        this.status = Objects.requireNonNull(status, "status");
        this.code = requireText(code, "code");
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
        this.fieldErrors = List.copyOf(Objects.requireNonNull(fieldErrors, "fieldErrors"));
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }

    UUID correlationId() {
        return correlationId;
    }

    List<FieldError> fieldErrors() {
        return fieldErrors;
    }

    static IdentityApiException validation(UUID correlationId, String field, String code, String message) {
        return new IdentityApiException(
                HttpStatus.BAD_REQUEST,
                "validation_failed",
                "Request validation failed.",
                correlationId,
                List.of(new FieldError(field, code, message)));
    }

    static IdentityApiException forbidden(UUID correlationId) {
        return new IdentityApiException(
                HttpStatus.FORBIDDEN,
                "forbidden",
                "Administrative authorization denied the operation.",
                correlationId,
                List.of());
    }

    static IdentityApiException notFound(UUID correlationId) {
        return new IdentityApiException(
                HttpStatus.NOT_FOUND,
                "not_found",
                "The requested Identity was not found.",
                correlationId,
                List.of());
    }

    static IdentityApiException conflict(UUID correlationId, String code, String message) {
        return new IdentityApiException(HttpStatus.CONFLICT, code, message, correlationId, List.of());
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
