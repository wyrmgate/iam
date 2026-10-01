package io.wyrmgate.iam.api.administration;

import io.wyrmgate.iam.api.administration.AdministrationApiModels.FieldError;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

final class AdministrationApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final UUID correlationId;
    private final List<FieldError> fieldErrors;

    AdministrationApiException(
            HttpStatus status, String code, String message, UUID correlationId, List<FieldError> fieldErrors) {
        super(message);
        this.status = status;
        this.code = code;
        this.correlationId = correlationId;
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    HttpStatus status() { return status; }
    String code() { return code; }
    UUID correlationId() { return correlationId; }
    List<FieldError> fieldErrors() { return fieldErrors; }

    static AdministrationApiException validation(
            UUID correlationId, String field, String code, String message) {
        return new AdministrationApiException(
                HttpStatus.BAD_REQUEST, "validation_failed", "Request validation failed.",
                correlationId, List.of(new FieldError(field, code, message)));
    }

    static AdministrationApiException forbidden(UUID correlationId, String code, String message) {
        return new AdministrationApiException(
                HttpStatus.FORBIDDEN, code, message, correlationId, List.of());
    }

    static AdministrationApiException notFound(UUID correlationId) {
        return new AdministrationApiException(
                HttpStatus.NOT_FOUND, "not_found", "Administrative resource was not found.",
                correlationId, List.of());
    }

    static AdministrationApiException conflict(UUID correlationId, String code, String message) {
        return new AdministrationApiException(
                HttpStatus.CONFLICT, code, message, correlationId, List.of());
    }

    static AdministrationApiException unavailable(UUID correlationId, String code, String message) {
        return new AdministrationApiException(
                HttpStatus.SERVICE_UNAVAILABLE, code, message, correlationId, List.of());
    }
}
