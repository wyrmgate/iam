package io.wyrmgate.iam.api.integration;

import io.wyrmgate.iam.integration.application.IntegrationAdministrationException;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.IdempotencyConflictException;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "io.wyrmgate.iam.api.integration")
final class IntegrationAdminApiErrorHandler {

    private final IdGenerator ids;

    IntegrationAdminApiErrorHandler(IdGenerator ids) {
        this.ids = ids;
    }

    @ExceptionHandler(IntegrationAdminApiException.class)
    ResponseEntity<IntegrationAdminApiModels.ErrorResponse> semantic(
            IntegrationAdminApiException error) {
        return response(error.status(), error.code(), error.getMessage(), error.correlationId());
    }

    @ExceptionHandler(IntegrationAdministrationException.class)
    ResponseEntity<IntegrationAdminApiModels.ErrorResponse> integration(
            IntegrationAdministrationException error, HttpServletRequest request) {
        UUID correlationId = IntegrationAdminApiRequestContext.forError(request, ids);
        HttpStatus status = switch (error.code()) {
            case "not_found", "connector_not_found" -> HttpStatus.NOT_FOUND;
            case "external_subject_already_registered",
                 "worker_permission_outside_scope",
                 "invalid_worker_scope" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return response(status, error.code(), error.getMessage(), correlationId);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<IntegrationAdminApiModels.ErrorResponse> idempotency(
            IdempotencyConflictException error, HttpServletRequest request) {
        return response(
                HttpStatus.CONFLICT, "idempotency_conflict",
                "The idempotency key was already used for a different request.",
                IntegrationAdminApiRequestContext.forError(request, ids));
    }

    @ExceptionHandler(StaleWriteException.class)
    ResponseEntity<IntegrationAdminApiModels.ErrorResponse> stale(
            StaleWriteException error, HttpServletRequest request) {
        return response(
                HttpStatus.PRECONDITION_FAILED, "stale_revision",
                "The supplied If-Match revision is stale.",
                IntegrationAdminApiRequestContext.forError(request, ids));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class})
    ResponseEntity<IntegrationAdminApiModels.ErrorResponse> invalid(
            Exception error, HttpServletRequest request) {
        return response(
                HttpStatus.BAD_REQUEST, "validation_failed", "Request validation failed.",
                IntegrationAdminApiRequestContext.forError(request, ids));
    }

    private static ResponseEntity<IntegrationAdminApiModels.ErrorResponse> response(
            HttpStatus status, String code, String message, UUID correlationId) {
        return ResponseEntity.status(status)
                .header("X-Correlation-Id", correlationId.toString())
                .header("Cache-Control", "no-store")
                .body(new IntegrationAdminApiModels.ErrorResponse(
                        code, message, correlationId));
    }
}
