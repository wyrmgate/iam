package io.wyrmgate.iam.api.catalog;

import io.wyrmgate.iam.api.catalog.CatalogApiModels.ErrorResponse;
import io.wyrmgate.iam.api.catalog.CatalogApiModels.FieldError;
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

@RestControllerAdvice(basePackages = "io.wyrmgate.iam.api.catalog")
public class CatalogApiErrorHandler {

    private final IdGenerator ids;

    public CatalogApiErrorHandler(IdGenerator ids) {
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @ExceptionHandler(CatalogApiException.class)
    ResponseEntity<ErrorResponse> catalogError(CatalogApiException error) {
        return response(
                error.status(), error.code(), error.getMessage(),
                error.correlationId(), error.fieldErrors());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ErrorResponse> idempotency(
            IdempotencyConflictException error, HttpServletRequest request) {
        return response(
                HttpStatus.CONFLICT, "idempotency_conflict",
                "The idempotency key was already used for a different request.",
                CatalogApiRequestContext.correlationIdForError(request, ids), List.of());
    }

    @ExceptionHandler(StaleWriteException.class)
    ResponseEntity<ErrorResponse> stale(
            StaleWriteException error, HttpServletRequest request) {
        return response(
                HttpStatus.PRECONDITION_FAILED, "stale_revision",
                "The supplied If-Match revision is stale.",
                CatalogApiRequestContext.correlationIdForError(request, ids), List.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> integrity(
            DataIntegrityViolationException error, HttpServletRequest request) {
        return response(
                HttpStatus.CONFLICT, "catalog_conflict",
                "The Catalog change conflicts with an existing resource or invariant.",
                CatalogApiRequestContext.correlationIdForError(request, ids), List.of());
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class})
    ResponseEntity<ErrorResponse> malformed(Exception error, HttpServletRequest request) {
        return response(
                HttpStatus.BAD_REQUEST, "validation_failed", "Request validation failed.",
                CatalogApiRequestContext.correlationIdForError(request, ids),
                List.of(new FieldError("request", "invalid", "The request is malformed.")));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> invalid(
            IllegalArgumentException error, HttpServletRequest request) {
        return response(
                HttpStatus.BAD_REQUEST, "validation_failed", "Request validation failed.",
                CatalogApiRequestContext.correlationIdForError(request, ids),
                List.of(new FieldError("request", "invalid", "The request is invalid.")));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> internal(Exception error, HttpServletRequest request) {
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR, "internal_failure",
                "The operation could not be completed.",
                CatalogApiRequestContext.correlationIdForError(request, ids), List.of());
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
