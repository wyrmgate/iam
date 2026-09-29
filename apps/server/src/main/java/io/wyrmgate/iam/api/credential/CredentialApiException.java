package io.wyrmgate.iam.api.credential;

import io.wyrmgate.iam.api.credential.CredentialApiModels.FieldError;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;

final class CredentialApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final UUID correlationId;
    private final List<FieldError> fieldErrors;

    CredentialApiException(
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

    static CredentialApiException validation(
            UUID id, String field, String code, String message) {
        return new CredentialApiException(
                HttpStatus.BAD_REQUEST,
                "validation_failed",
                "Request validation failed.",
                id,
                List.of(new FieldError(field, code, message)));
    }

    static CredentialApiException forbidden(UUID id) {
        return new CredentialApiException(
                HttpStatus.FORBIDDEN,
                "forbidden",
                "Administrative authorization denied the operation.",
                id,
                List.of());
    }

    static CredentialApiException notFound(UUID id) {
        return new CredentialApiException(
                HttpStatus.NOT_FOUND,
                "not_found",
                "The requested Credential resource was not found.",
                id,
                List.of());
    }

    static CredentialApiException conflict(
            UUID id, String code, String message) {
        return new CredentialApiException(
                HttpStatus.CONFLICT, code, message, id, List.of());
    }
}
