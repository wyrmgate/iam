package io.wyrmgate.iam.api.governance;

import io.wyrmgate.iam.api.governance.GovernanceApprovalApiModels.ErrorResponse;
import io.wyrmgate.iam.api.governance.GovernanceApprovalApiModels.FieldError;
import io.wyrmgate.iam.governance.application.ApprovalCommandException;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.IdempotencyConflictException;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(
        basePackages = "io.wyrmgate.iam.api.governance")
public class GovernanceApprovalApiErrorHandler {

    private final IdGenerator ids;

    public GovernanceApprovalApiErrorHandler(IdGenerator ids) {
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @ExceptionHandler(GovernanceApprovalApiException.class)
    ResponseEntity<ErrorResponse> api(
            GovernanceApprovalApiException error) {
        return response(
                error.status(),
                error.code(),
                error.getMessage(),
                error.correlationId(),
                error.fieldErrors());
    }

    @ExceptionHandler(ApprovalCommandException.class)
    ResponseEntity<ErrorResponse> command(
            ApprovalCommandException error,
            HttpServletRequest request) {
        UUID correlationId =
                GovernanceApprovalApiRequestContext
                        .correlationIdForError(request, ids);
        HttpStatus status = switch (error.code()) {
            case "approval_case_not_found" -> HttpStatus.NOT_FOUND;
            case "approval_actor_not_participant",
                    "approval_self_decision_denied" ->
                    HttpStatus.FORBIDDEN;
            default -> HttpStatus.CONFLICT;
        };
        return response(
                status,
                error.code(),
                error.getMessage(),
                correlationId,
                List.of());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ErrorResponse> idempotency(
            IdempotencyConflictException error,
            HttpServletRequest request) {
        return response(
                HttpStatus.CONFLICT,
                "idempotency_conflict",
                "The idempotency key was already used for a different request.",
                GovernanceApprovalApiRequestContext
                        .correlationIdForError(request, ids),
                List.of());
    }

    @ExceptionHandler(StaleWriteException.class)
    ResponseEntity<ErrorResponse> stale(
            StaleWriteException error,
            HttpServletRequest request) {
        return response(
                HttpStatus.PRECONDITION_FAILED,
                "stale_revision",
                "The supplied If-Match revision is stale.",
                GovernanceApprovalApiRequestContext
                        .correlationIdForError(request, ids),
                List.of());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> invalid(
            IllegalArgumentException error,
            HttpServletRequest request) {
        return response(
                HttpStatus.BAD_REQUEST,
                "validation_failed",
                "Request validation failed.",
                GovernanceApprovalApiRequestContext
                        .correlationIdForError(request, ids),
                List.of(new FieldError(
                        "request",
                        "invalid",
                        "The request is invalid.")));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> internal(
            Exception error,
            HttpServletRequest request) {
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "internal_failure",
                "The operation could not be completed.",
                GovernanceApprovalApiRequestContext
                        .correlationIdForError(request, ids),
                List.of());
    }

    private static ResponseEntity<ErrorResponse> response(
            HttpStatus status,
            String code,
            String message,
            UUID correlationId,
            List<FieldError> fieldErrors) {
        return ResponseEntity.status(status)
                .header(
                        "X-Correlation-Id",
                        correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new ErrorResponse(
                        code,
                        message,
                        correlationId,
                        fieldErrors));
    }
}
