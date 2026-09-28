package io.wyrmgate.iam.api.governance;

import io.wyrmgate.iam.api.governance.GovernanceApiModels.FieldError;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;

final class GovernanceApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final UUID correlationId;
    private final List<FieldError> fieldErrors;

    GovernanceApiException(
            HttpStatus status,
            String code,
            String message,
            UUID correlationId,
            List<FieldError> fieldErrors) {
        super(message);
        this.status = Objects.requireNonNull(status, "status");
        this.code = Objects.requireNonNull(code, "code");
        this.correlationId = Objects.requireNonNull(
                correlationId, "correlationId");
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    HttpStatus status() { return status; }
    String code() { return code; }
    UUID correlationId() { return correlationId; }
    List<FieldError> fieldErrors() { return fieldErrors; }

    static GovernanceApiException validation(
            UUID id,
            String field,
            String code,
            String message) {
        return new GovernanceApiException(
                HttpStatus.BAD_REQUEST,
                "validation_failed",
                "Request validation failed.",
                id,
                List.of(new FieldError(
                        field, code, message)));
    }

    static GovernanceApiException forbidden(UUID id) {
        return new GovernanceApiException(
                HttpStatus.FORBIDDEN,
                "forbidden",
                "The authenticated actor is not authorized for this Governance operation.",
                id,
                List.of());
    }

    static GovernanceApiException notFound(UUID id) {
        return notFound(
                id, "not_found",
                "The requested Governance resource was not found.");
    }

    static GovernanceApiException notFound(
            UUID id, String code, String message) {
        return new GovernanceApiException(
                HttpStatus.NOT_FOUND,
                code,
                message,
                id,
                List.of());
    }

    static GovernanceApiException conflict(
            UUID id, String code, String message) {
        return new GovernanceApiException(
                HttpStatus.CONFLICT,
                code,
                message,
                id,
                List.of());
    }
}
