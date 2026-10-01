package io.wyrmgate.iam.api.administration;

import io.wyrmgate.iam.api.administration.AdministrationApiModels.FieldError;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;

final class AdministrationApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final UUID correlationId;
    private final List<FieldError> fieldErrors;

    AdministrationApiException(
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

    static AdministrationApiException validation(
            UUID id, String field, String code, String message) {
        return new AdministrationApiException(
                HttpStatus.BAD_REQUEST,
                "validation_failed",
                "Request validation failed.",
                id,
                List.of(new FieldError(field, code, message)));
    }

    static AdministrationApiException conflict(
            UUID id, String code, String message) {
        return new AdministrationApiException(
                HttpStatus.CONFLICT, code, message, id, List.of());
    }
}
