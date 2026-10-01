package io.wyrmgate.iam.api.administration;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorityException;
import io.wyrmgate.iam.api.administration.AdministrationApiModels.ErrorResponse;
import io.wyrmgate.iam.api.administration.AdministrationApiModels.FieldError;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.IdempotencyConflictException;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
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

@RestControllerAdvice(basePackages = "io.wyrmgate.iam.api.administration")
public final class AdministrationApiErrorHandler {
    private final IdGenerator ids;

    public AdministrationApiErrorHandler(IdGenerator ids) { this.ids = ids; }

    @ExceptionHandler(AdministrationApiException.class)
    ResponseEntity<ErrorResponse> api(AdministrationApiException error) {
        return response(error.status(), error.code(), error.getMessage(),
                error.correlationId(), error.fieldErrors());
    }

    @ExceptionHandler(AdministrativeAuthorityException.class)
    ResponseEntity<ErrorResponse> authority(
            AdministrativeAuthorityException error, HttpServletRequest request) {
        UUID correlationId = AdministrationApiRequestContext.correlationIdForError(request, ids);
        String code = error.code();
        HttpStatus status;
        if (code.endsWith("_not_found")) status = HttpStatus.NOT_FOUND;
        else if (code.endsWith("_unavailable")) status = HttpStatus.SERVICE_UNAVAILABLE;
        else if (code.contains("not_cancellable")
                || code.contains("not_pending")
                || code.contains("not_requestable")
                || code.contains("approval_not_satisfied")
                || code.contains("stale_elevation")) status = HttpStatus.CONFLICT;
        else if (code.contains("invalid_validity")) status = HttpStatus.BAD_REQUEST;
        else status = HttpStatus.FORBIDDEN;
        return response(status, code, error.getMessage(), correlationId, List.of());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ErrorResponse> idempotency(
            IdempotencyConflictException error, HttpServletRequest request) {
        return response(
                HttpStatus.CONFLICT, "idempotency_conflict",
                "The idempotency key was already used for a different request.",
                AdministrationApiRequestContext.correlationIdForError(request, ids), List.of());
    }

    @ExceptionHandler(StaleWriteException.class)
    ResponseEntity<ErrorResponse> stale(StaleWriteException error, HttpServletRequest request) {
        return response(
                HttpStatus.PRECONDITION_FAILED, "stale_revision",
                "The supplied If-Match revision is stale.",
                AdministrationApiRequestContext.correlationIdForError(request, ids), List.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> integrity(DataIntegrityViolationException error, HttpServletRequest request) {
        return response(
                HttpStatus.CONFLICT, "administration_conflict",
                "The Administration change conflicts with an existing resource or invariant.",
                AdministrationApiRequestContext.correlationIdForError(request, ids), List.of());
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class})
    ResponseEntity<ErrorResponse> malformed(Exception error, HttpServletRequest request) {
        return response(
                HttpStatus.BAD_REQUEST, "validation_failed", "Request validation failed.",
                AdministrationApiRequestContext.correlationIdForError(request, ids),
                List.of(new FieldError("request", "invalid", "The request is malformed.")));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> invalid(IllegalArgumentException error, HttpServletRequest request) {
        return response(
                HttpStatus.BAD_REQUEST, "validation_failed", "Request validation failed.",
                AdministrationApiRequestContext.correlationIdForError(request, ids),
                List.of(new FieldError("request", "invalid", "The request is invalid.")));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> internal(Exception error, HttpServletRequest request) {
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR, "internal_failure",
                "The operation could not be completed.",
                AdministrationApiRequestContext.correlationIdForError(request, ids), List.of());
    }

    private static ResponseEntity<ErrorResponse> response(
            HttpStatus status, String code, String message, UUID correlationId, List<FieldError> errors) {
        return ResponseEntity.status(status)
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new ErrorResponse(code, message, correlationId, errors));
    }
}
