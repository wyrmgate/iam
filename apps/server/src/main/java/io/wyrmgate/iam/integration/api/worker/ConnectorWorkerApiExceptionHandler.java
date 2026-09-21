package io.wyrmgate.iam.integration.api.worker;

import io.wyrmgate.iam.integration.application.WorkerProtocolException;
import io.wyrmgate.iam.platform.id.IdGenerator;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ConnectorWorkerController.class)
final class ConnectorWorkerApiExceptionHandler {

    private final IdGenerator ids;

    ConnectorWorkerApiExceptionHandler(IdGenerator ids) {
        this.ids = ids;
    }

    @ExceptionHandler(WorkerProtocolException.class)
    ResponseEntity<Map<String,Object>> protocol(WorkerProtocolException exception) {
        UUID correlationId = ids.nextId();
        HttpStatus status = switch (exception.code()) {
            case "protocol_incompatible", "session_invalid", "stale_lease",
                 "completion_conflict", "observation_batch_conflict", "observation_sequence_conflict",
                 "work_not_running", "work_not_found" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
                .header("Cache-Control", "no-store")
                .header("X-Correlation-Id", correlationId.toString())
                .body(Map.of(
                        "code", exception.code(),
                        "message", exception.getMessage(),
                        "correlationId", correlationId.toString()));
    }
}
