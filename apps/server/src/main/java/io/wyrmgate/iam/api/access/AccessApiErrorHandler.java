package io.wyrmgate.iam.api.access;

import io.wyrmgate.iam.access.application.AccessAssignmentCommandException;
import io.wyrmgate.iam.api.access.AccessApiModels.ErrorResponse;
import io.wyrmgate.iam.api.access.AccessApiModels.FieldError;
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
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackages = "io.wyrmgate.iam.api.access")
public class AccessApiErrorHandler {

    private final IdGenerator ids;

    public AccessApiErrorHandler(IdGenerator ids) {
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @ExceptionHandler(AccessApiException.class)
    ResponseEntity<ErrorResponse> apiError(AccessApiException error) {
        return response(
                error.status(), error.code(), error.getMessage(),
                error.correlationId(), error.fieldErrors());
    }

    @ExceptionHandler(AccessAssignmentCommandException.class)
    ResponseEntity<ErrorResponse> commandError(
            AccessAssignmentCommandException error,
            HttpServletRequest request) {
        UUID correlationId =
                AccessApiRequestContext.correlationIdForError(
                        request, ids);
        HttpStatus status = error.code().endsWith("_not_found")
                || "access_assignment_not_found".equals(error.code())
                ? HttpStatus.NOT_FOUND
                : HttpStatus.CONFLICT;
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
                AccessApiRequestContext.correlationIdForError(
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
                AccessApiRequestContext.correlationIdForError(
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
                AccessApiRequestContext.correlationIdForError(
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
                AccessApiRequestContext.correlationIdForError(
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
                AccessApiRequestContext.correlationIdForError(
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
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new ErrorResponse(
                        code,
                        message,
                        correlationId,
                        fieldErrors));
    }
}
