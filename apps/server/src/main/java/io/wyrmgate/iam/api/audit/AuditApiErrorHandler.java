package io.wyrmgate.iam.api.audit;

import io.wyrmgate.iam.api.audit.AuditApiModels.ErrorResponse;
import io.wyrmgate.iam.api.audit.AuditApiModels.FieldError;
import io.wyrmgate.iam.platform.id.IdGenerator;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackages = "io.wyrmgate.iam.api.audit")
public class AuditApiErrorHandler {

    private final IdGenerator ids;

    public AuditApiErrorHandler(IdGenerator ids) {
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @ExceptionHandler(AuditApiException.class)
    ResponseEntity<ErrorResponse> auditError(
            AuditApiException error) {
        return response(
                error.status(),
                error.code(),
                error.getMessage(),
                error.correlationId(),
                error.fieldErrors());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> malformed(
            MethodArgumentTypeMismatchException error,
            HttpServletRequest request) {
        return response(
                HttpStatus.BAD_REQUEST,
                "validation_failed",
                "Request validation failed.",
                AuditApiRequestContext.correlationIdForError(
                        request, ids),
                List.of(new FieldError(
                        error.getName(),
                        "invalid",
                        "The request parameter is invalid.")));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> invalid(
            IllegalArgumentException error,
            HttpServletRequest request) {
        return response(
                HttpStatus.BAD_REQUEST,
                "validation_failed",
                "Request validation failed.",
                AuditApiRequestContext.correlationIdForError(
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
                AuditApiRequestContext.correlationIdForError(
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
