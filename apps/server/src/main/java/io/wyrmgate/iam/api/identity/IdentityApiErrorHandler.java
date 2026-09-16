package io.wyrmgate.iam.api.identity;

import io.wyrmgate.iam.api.identity.IdentityApiModels.ErrorResponse;
import io.wyrmgate.iam.api.identity.IdentityApiModels.FieldError;
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

/** Stable semantic public error envelope for Identity control-plane HTTP operations. */
@RestControllerAdvice(basePackages = "io.wyrmgate.iam.api.identity")
public class IdentityApiErrorHandler {

    private final IdGenerator ids;

    public IdentityApiErrorHandler(IdGenerator ids) {
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @ExceptionHandler(IdentityApiException.class)
    ResponseEntity<ErrorResponse> identityApiError(IdentityApiException error) {
        return response(error.status(), error.code(), error.getMessage(), error.correlationId(), error.fieldErrors());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ErrorResponse> idempotencyConflict(
            IdempotencyConflictException error,
            HttpServletRequest request) {
        UUID correlationId = IdentityApiRequestContext.correlationIdForError(request, ids);
        return response(
                HttpStatus.CONFLICT,
                "idempotency_conflict",
                "The idempotency key was already used for a different request.",
                correlationId,
                List.of());
    }

    @ExceptionHandler(StaleWriteException.class)
    ResponseEntity<ErrorResponse> staleRevision(StaleWriteException error, HttpServletRequest request) {
        UUID correlationId = IdentityApiRequestContext.correlationIdForError(request, ids);
        return response(
                HttpStatus.PRECONDITION_FAILED,
                "stale_revision",
                "The supplied If-Match revision is stale.",
                correlationId,
                List.of());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class})
    ResponseEntity<ErrorResponse> malformedRequest(Exception error, HttpServletRequest request) {
        UUID correlationId = IdentityApiRequestContext.correlationIdForError(request, ids);
        return response(
                HttpStatus.BAD_REQUEST,
                "validation_failed",
                "Request validation failed.",
                correlationId,
                List.of(new FieldError("request", "invalid", "The request is malformed.")));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> invalidArgument(IllegalArgumentException error, HttpServletRequest request) {
        UUID correlationId = IdentityApiRequestContext.correlationIdForError(request, ids);
        return response(
                HttpStatus.BAD_REQUEST,
                "validation_failed",
                "Request validation failed.",
                correlationId,
                List.of(new FieldError("request", "invalid", "The request is invalid.")));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> internalFailure(Exception error, HttpServletRequest request) {
        UUID correlationId = IdentityApiRequestContext.correlationIdForError(request, ids);
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "internal_failure",
                "The operation could not be completed.",
                correlationId,
                List.of());
    }

    private static ResponseEntity<ErrorResponse> response(
            HttpStatus status,
            String code,
            String message,
            UUID correlationId,
            List<FieldError> fieldErrors) {
        return ResponseEntity.status(status)
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new ErrorResponse(code, message, correlationId, fieldErrors));
    }
}
