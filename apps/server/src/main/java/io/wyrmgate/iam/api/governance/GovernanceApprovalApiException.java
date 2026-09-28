package io.wyrmgate.iam.api.governance;

import io.wyrmgate.iam.api.governance.GovernanceApprovalApiModels.FieldError;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;

final class GovernanceApprovalApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final UUID correlationId;
    private final List<FieldError> fieldErrors;

    GovernanceApprovalApiException(
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

    static GovernanceApprovalApiException validation(
            UUID correlationId,
            String field,
            String code,
            String message) {
        return new GovernanceApprovalApiException(
                HttpStatus.BAD_REQUEST,
                "validation_failed",
                "Request validation failed.",
                correlationId,
                List.of(new FieldError(field, code, message)));
    }

    static GovernanceApprovalApiException notFound(
            UUID correlationId) {
        return new GovernanceApprovalApiException(
                HttpStatus.NOT_FOUND,
                "not_found",
                "The requested approval resource was not found.",
                correlationId,
                List.of());
    }

    static GovernanceApprovalApiException conflict(
            UUID correlationId,
            String code,
            String message) {
        return new GovernanceApprovalApiException(
                HttpStatus.CONFLICT,
                code,
                message,
                correlationId,
                List.of());
    }
}
