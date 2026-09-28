package io.wyrmgate.iam.api.governance;

import io.wyrmgate.iam.api.governance.GovernanceApiModels.ErrorResponse;
import io.wyrmgate.iam.api.governance.GovernanceApiModels.FieldError;
import io.wyrmgate.iam.governance.application.AccessRequestCommandException;
import io.wyrmgate.iam.governance.application.ApprovalCommandException;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.IdempotencyConflictException;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(
        basePackages = "io.wyrmgate.iam.api.governance")
public class GovernanceApiErrorHandler {

    private static final Set<String> APPROVAL_FORBIDDEN =
            Set.of(
                    "approval_actor_not_approver",
                    "approval_self_decision_forbidden");

    private final IdGenerator ids;

    public GovernanceApiErrorHandler(IdGenerator ids) {
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @ExceptionHandler(GovernanceApiException.class)
    ResponseEntity<ErrorResponse> apiError(
            GovernanceApiException error) {
        return response(
                error.status(),
                error.code(),
                error.getMessage(),
                error.correlationId(),
                error.fieldErrors());
    }

    @ExceptionHandler(AccessRequestCommandException.class)
    ResponseEntity<ErrorResponse> requestCommand(
            AccessRequestCommandException error,
            HttpServletRequest request) {
        HttpStatus status =
                error.code().endsWith("_not_found")
                        ? HttpStatus.NOT_FOUND
                        : HttpStatus.CONFLICT;
        return response(
                status,
                error.code(),
                error.getMessage(),
                GovernanceApiRequestContext
                        .correlationIdForError(
                                request, ids),
                List.of());
    }

    @ExceptionHandler(ApprovalCommandException.class)
    ResponseEntity<ErrorResponse> approvalCommand(
            ApprovalCommandException error,
            HttpServletRequest request) {
        HttpStatus status;
        if ("approval_case_not_found".equals(
                error.code())) {
            status = HttpStatus.NOT_FOUND;
        } else if (APPROVAL_FORBIDDEN.contains(
                error.code())) {
            status = HttpStatus.FORBIDDEN;
        } else {
            status = HttpStatus.CONFLICT;
        }
        return response(
                status,
                error.code(),
                error.getMessage(),
                GovernanceApiRequestContext
                        .correlationIdForError(
                                request, ids),
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
                GovernanceApiRequestContext
                        .correlationIdForError(
                                request, ids),
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
                GovernanceApiRequestContext
                        .correlationIdForError(
                                request, ids),
                List.of());
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class})
    ResponseEntity<ErrorResponse> malformed(
            Exception error,
            HttpServletRequest request) {
        return response(
                HttpStatus.BAD_REQUEST,
                "validation_failed",
                "Request validation failed.",
                GovernanceApiRequestContext
                        .correlationIdForError(
                                request, ids),
                List.of(new FieldError(
                        "request",
                        "invalid",
                        "The request is malformed.")));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> invalid(
            IllegalArgumentException error,
            HttpServletRequest request) {
        return response(
                HttpStatus.BAD_REQUEST,
                "validation_failed",
                "Request validation failed.",
                GovernanceApiRequestContext
                        .correlationIdForError(
                                request, ids),
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
                GovernanceApiRequestContext
                        .correlationIdForError(
                                request, ids),
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
                .header(
                        HttpHeaders.CACHE_CONTROL,
                        "no-store")
                .body(new ErrorResponse(
                        code,
                        message,
                        correlationId,
                        fieldErrors));
    }
}
