package io.wyrmgate.iam.api.console;

import java.util.UUID;
import org.springframework.http.HttpStatus;

final class RequestsAndApprovalsApiException extends RuntimeException {
    private final HttpStatus status;
    private final UUID correlationId;
    private final String code;

    private RequestsAndApprovalsApiException(
            HttpStatus status,
            UUID correlationId,
            String code,
            String message) {
        super(message);
        this.status = status;
        this.correlationId = correlationId;
        this.code = code;
    }

    static RequestsAndApprovalsApiException validation(UUID correlationId, String message) {
        return new RequestsAndApprovalsApiException(
                HttpStatus.BAD_REQUEST,
                correlationId,
                "invalid_requests_query",
                message);
    }

    static RequestsAndApprovalsApiException unavailable(UUID correlationId) {
        return new RequestsAndApprovalsApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                correlationId,
                "requests_page_unavailable",
                "Requests & Approvals data is temporarily unavailable.");
    }

    HttpStatus status() { return status; }
    UUID correlationId() { return correlationId; }
    String code() { return code; }
}
