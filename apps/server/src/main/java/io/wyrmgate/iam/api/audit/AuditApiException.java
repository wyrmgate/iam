package io.wyrmgate.iam.api.audit;

import io.wyrmgate.iam.api.audit.AuditApiModels.FieldError;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;

final class AuditApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final UUID correlationId;
    private final List<FieldError> fieldErrors;

    AuditApiException(
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

    static AuditApiException validation(
            UUID correlationId,
            String field,
            String fieldCode,
            String message) {
        return new AuditApiException(
                HttpStatus.BAD_REQUEST,
                "validation_failed",
                "Request validation failed.",
                correlationId,
                List.of(new FieldError(field, fieldCode, message)));
    }

    static AuditApiException forbidden(UUID correlationId) {
        return new AuditApiException(
                HttpStatus.FORBIDDEN,
                "forbidden",
                "Administrative authorization denied the operation.",
                correlationId,
                List.of());
    }

    static AuditApiException notFound(UUID correlationId) {
        return new AuditApiException(
                HttpStatus.NOT_FOUND,
                "not_found",
                "The requested Audit resource was not found.",
                correlationId,
                List.of());
    }

    static AuditApiException conflict(
            UUID correlationId,
            String code,
            String message) {
        return new AuditApiException(
                HttpStatus.CONFLICT,
                code,
                message,
                correlationId,
                List.of());
    }

    static AuditApiException gone(
            UUID correlationId,
            String code,
            String message) {
        return new AuditApiException(
                HttpStatus.GONE,
                code,
                message,
                correlationId,
                List.of());
    }

    static AuditApiException unavailable(
            UUID correlationId,
            String code,
            String message) {
        return new AuditApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                code,
                message,
                correlationId,
                List.of());
    }
}
