package io.wyrmgate.iam.identity.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.identity.domain.PrincipalLifecycleState;
import io.wyrmgate.iam.integration.application.IntegrationPrincipalProvisioningFactSink;
import io.wyrmgate.iam.platform.persistence.ClaimedOutboxEvent;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class PrincipalProvisioningResultProcessor {

    private static final TypeReference<Map<String,Object>> MAP_TYPE =
            new TypeReference<>() {};
    private static final Duration CLAIM_LEASE = Duration.ofSeconds(30);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(10);
    private static final int BATCH_SIZE = 100;

    private final JdbcOutboxRepository outbox;
    private final PrincipalCommandService principals;
    private final ObjectMapper json;
    private final Clock clock;

    public PrincipalProvisioningResultProcessor(
            JdbcOutboxRepository outbox,
            PrincipalCommandService principals,
            ObjectMapper json) {
        this(outbox, principals, json, Clock.systemUTC());
    }

    PrincipalProvisioningResultProcessor(
            JdbcOutboxRepository outbox,
            PrincipalCommandService principals,
            ObjectMapper json,
            Clock clock) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.principals = Objects.requireNonNull(principals, "principals");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public int processAvailable() {
        Instant now = clock.instant();
        List<ClaimedOutboxEvent> claimed = outbox.claimPending(
                Set.of(IntegrationPrincipalProvisioningFactSink
                        .PRINCIPAL_PROVISIONING_SUCCEEDED),
                now,
                CLAIM_LEASE,
                BATCH_SIZE);
        int processed = 0;
        for (ClaimedOutboxEvent item : claimed) {
            try {
                var event = item.event();
                if (event.eventVersion() != 1
                        || !"desired-principal".equals(event.aggregateType())
                        || event.aggregateId() == null
                        || event.aggregateRevision() == null) {
                    outbox.markTerminalFailure(
                            item.tenant(),
                            event.eventId(),
                            "principal_provisioning_result_invalid");
                    continue;
                }
                Map<String,Object> payload = read(event.payloadJson());
                UUID identityId = UUID.fromString(required(payload, "identityId"));
                UUID targetId = UUID.fromString(
                        required(payload, "applicationTargetId"));
                String operation = required(payload, "operationType");
                String providerPrincipalId = required(
                        payload, "providerPrincipalId");
                PrincipalLifecycleState lifecycle =
                        ("DISABLE_PRINCIPAL".equals(operation)
                                || "DEACTIVATE_PRINCIPAL".equals(operation))
                                ? PrincipalLifecycleState.DISABLED
                                : PrincipalLifecycleState.ACTIVE;
                UUID correlationId = event.correlationId() == null
                        ? event.eventId()
                        : event.correlationId();
                principals.applyProvisioningState(
                        item.tenant(),
                        identityId,
                        targetId,
                        providerPrincipalId,
                        lifecycle,
                        clock.instant(),
                        correlationId,
                        event.eventId());
                outbox.markPublished(
                        item.tenant(), event.eventId(), clock.instant());
                processed++;
            } catch (IllegalArgumentException invalid) {
                outbox.markTerminalFailure(
                        item.tenant(),
                        item.event().eventId(),
                        "principal_provisioning_result_invalid");
            } catch (RuntimeException retryable) {
                outbox.markFailed(
                        item.tenant(),
                        item.event().eventId(),
                        clock.instant().plus(RETRY_DELAY),
                        "principal_provisioning_result_failed");
            }
        }
        return processed;
    }

    private Map<String,Object> read(String value) {
        try {
            return json.readValue(value, MAP_TYPE);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("invalid provisioning result payload", invalid);
        }
    }

    private static String required(Map<String,Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException(key + " must not be blank");
        }
        return String.valueOf(value);
    }
}
