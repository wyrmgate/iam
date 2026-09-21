package io.wyrmgate.iam.api.integration;

import java.util.UUID;
import org.springframework.http.HttpStatus;

final class IntegrationAdminApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final UUID correlationId;

    IntegrationAdminApiException(HttpStatus status, String code, String message, UUID correlationId) {
        super(message);
        this.status = status;
        this.code = code;
        this.correlationId = correlationId;
    }

    HttpStatus status() { return status; }
    String code() { return code; }
    UUID correlationId() { return correlationId; }

    static IntegrationAdminApiException validation(UUID id, String message) {
        return new IntegrationAdminApiException(HttpStatus.BAD_REQUEST, "validation_failed", message, id);
    }

    static IntegrationAdminApiException forbidden(UUID id) {
        return new IntegrationAdminApiException(
                HttpStatus.FORBIDDEN, "forbidden",
                "Administrative authorization denied the operation.", id);
    }

    static IntegrationAdminApiException notFound(UUID id) {
        return new IntegrationAdminApiException(
                HttpStatus.NOT_FOUND, "not_found",
                "The requested Integration resource was not found.", id);
    }

    static IntegrationAdminApiException conflict(UUID id, String code, String message) {
        return new IntegrationAdminApiException(HttpStatus.CONFLICT, code, message, id);
    }
}
