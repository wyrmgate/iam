package io.wyrmgate.iam.api.authentication;

import io.wyrmgate.iam.api.authentication.AuthenticationApiModels.FieldError;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;

final class AuthenticationApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final UUID correlationId;
    private final List<FieldError> fieldErrors;

    AuthenticationApiException(
            HttpStatus status,
            String code,
            String message,
            UUID correlationId,
            List<FieldError> fieldErrors) {
        super(message);
        this.status = Objects.requireNonNull(status, "status");
        this.code = Objects.requireNonNull(code, "code");
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    HttpStatus status() { return status; }
    String code() { return code; }
    UUID correlationId() { return correlationId; }
    List<FieldError> fieldErrors() { return fieldErrors; }

    static AuthenticationApiException validation(UUID id, String field, String code, String message) {
        return new AuthenticationApiException(
                HttpStatus.BAD_REQUEST, "validation_failed", "Request validation failed.", id,
                List.of(new FieldError(field, code, message)));
    }

    static AuthenticationApiException forbidden(UUID id) {
        return new AuthenticationApiException(
                HttpStatus.FORBIDDEN, "forbidden",
                "Administrative authorization denied the operation.", id, List.of());
    }

    static AuthenticationApiException notFound(UUID id) {
        return new AuthenticationApiException(
                HttpStatus.NOT_FOUND, "not_found",
                "The requested Authentication resource was not found.", id, List.of());
    }

    static AuthenticationApiException conflict(UUID id, String code, String message) {
        return new AuthenticationApiException(HttpStatus.CONFLICT, code, message, id, List.of());
    }

    static AuthenticationApiException unavailable(UUID id) {
        return new AuthenticationApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "authentication_dependency_unavailable",
                "A mandatory authentication security evaluation is unavailable.", id, List.of());
    }
}