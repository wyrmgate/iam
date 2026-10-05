package io.wyrmgate.iam.api.authentication;

import io.wyrmgate.iam.api.authentication.AuthenticationApiModels.ErrorResource;
import io.wyrmgate.iam.api.authentication.AuthenticationApiModels.FieldError;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.IdempotencyConflictException;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackages = "io.wyrmgate.iam.api.authentication")
public class AuthenticationApiErrorHandler {

    private final IdGenerator ids;

    public AuthenticationApiErrorHandler(IdGenerator ids) {
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @ExceptionHandler(AuthenticationApiException.class)
    ResponseEntity<ErrorResource> authenticationError(AuthenticationApiException error) {
        return response(
                error.status(), error.code(), error.getMessage(), error.correlationId(), error.fieldErrors());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ErrorResource> idempotency(
            IdempotencyConflictException error, HttpServletRequest request) {
        return response(
                HttpStatus.CONFLICT, "idempotency_conflict",
                "The idempotency key was already used for a different request.",
                AuthenticationApiRequestContext.correlationIdForError(request, ids), List.of());
    }

    @ExceptionHandler(StaleWriteException.class)
    ResponseEntity<ErrorResource> stale(
            StaleWriteException error, HttpServletRequest request) {
        return response(
                HttpStatus.PRECONDITION_FAILED, "stale_revision",
                "The supplied If-Match revision is stale.",
                AuthenticationApiRequestContext.correlationIdForError(request, ids), List.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResource> integrity(
            DataIntegrityViolationException error, HttpServletRequest request) {
        return response(
                HttpStatus.CONFLICT, "authentication_conflict",
                "The Authentication change conflicts with an existing resource or invariant.",
                AuthenticationApiRequestContext.correlationIdForError(request, ids), List.of());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class})
    ResponseEntity<ErrorResource> malformed(Exception error, HttpServletRequest request) {
        return response(
                HttpStatus.BAD_REQUEST, "validation_failed", "Request validation failed.",
                AuthenticationApiRequestContext.correlationIdForError(request, ids),
                List.of(new FieldError("request", "invalid", "The request is malformed.")));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResource> invalid(IllegalArgumentException error, HttpServletRequest request) {
        return response(
                HttpStatus.BAD_REQUEST, "validation_failed", "Request validation failed.",
                AuthenticationApiRequestContext.correlationIdForError(request, ids),
                List.of(new FieldError("request", "invalid", "The request is invalid.")));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ErrorResource> unavailable(IllegalStateException error, HttpServletRequest request) {
        return response(
                HttpStatus.SERVICE_UNAVAILABLE,
                "authentication_dependency_unavailable",
                "The Authentication operation could not complete because a mandatory security condition is unavailable.",
                AuthenticationApiRequestContext.correlationIdForError(request, ids), List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResource> internal(Exception error, HttpServletRequest request) {
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR, "internal_failure",
                "The operation could not be completed.",
                AuthenticationApiRequestContext.correlationIdForError(request, ids), List.of());
    }

    private static ResponseEntity<ErrorResource> response(
            HttpStatus status, String code, String message,
            UUID correlationId, List<FieldError> fieldErrors) {
        return ResponseEntity.status(status)
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new ErrorResource(code, message, correlationId, fieldErrors));
    }
}