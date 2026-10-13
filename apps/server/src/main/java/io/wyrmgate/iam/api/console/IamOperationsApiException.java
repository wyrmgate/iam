package io.wyrmgate.iam.api.console;
import java.util.UUID;
import org.springframework.http.HttpStatus;
final class IamOperationsApiException extends RuntimeException {
    private final HttpStatus status; private final UUID correlationId; private final String code;
    private IamOperationsApiException(HttpStatus status, UUID correlationId, String code, String message) {
        super(message); this.status = status; this.correlationId = correlationId; this.code = code;
    }
    static IamOperationsApiException validation(UUID correlationId, String message) {
        return new IamOperationsApiException(HttpStatus.BAD_REQUEST, correlationId, "invalid_overview_query", message);
    }
    HttpStatus status() { return status; } UUID correlationId() { return correlationId; } String code() { return code; }
}
