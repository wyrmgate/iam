package io.wyrmgate.iam.api.console;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
@RestControllerAdvice(assignableTypes = IamOperationsController.class)
final class IamOperationsApiExceptionHandler {
    @ExceptionHandler(IamOperationsApiException.class)
    ResponseEntity<Map<String,Object>> handle(IamOperationsApiException exception) {
        return ResponseEntity.status(exception.status()).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Correlation-Id", exception.correlationId().toString())
                .body(Map.of("code", exception.code(), "message", exception.getMessage(), "correlationId", exception.correlationId().toString()));
    }
}
