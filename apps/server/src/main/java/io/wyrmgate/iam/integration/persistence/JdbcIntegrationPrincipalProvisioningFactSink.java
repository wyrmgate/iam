package io.wyrmgate.iam.integration.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.integration.application.IntegrationPrincipalProvisioningFactSink;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.OutboxEvent;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class JdbcIntegrationPrincipalProvisioningFactSink
        implements IntegrationPrincipalProvisioningFactSink {

    private final JdbcOutboxRepository outbox;
    private final IdGenerator ids;
    private final ObjectMapper json;

    public JdbcIntegrationPrincipalProvisioningFactSink(
            JdbcOutboxRepository outbox,
            IdGenerator ids,
            ObjectMapper json) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public void succeeded(
            TenantContext tenant,
            UUID desiredPrincipalId,
            long desiredRevision,
            UUID identityId,
            UUID applicationTargetId,
            String operationType,
            String providerPrincipalId,
            Instant occurredAt,
            UUID correlationId,
            UUID causationId) {
        Map<String,Object> payload = new LinkedHashMap<>();
        payload.put("identityId", identityId.toString());
        payload.put("applicationTargetId", applicationTargetId.toString());
        payload.put("operationType", operationType);
        payload.put("providerPrincipalId", providerPrincipalId);
        outbox.append(
                tenant,
                new OutboxEvent(
                        ids.nextId(),
                        PRINCIPAL_PROVISIONING_SUCCEEDED,
                        1,
                        "desired-principal",
                        desiredPrincipalId,
                        desiredRevision,
                        occurredAt,
                        correlationId,
                        causationId,
                        writeJson(payload)),
                occurredAt);
    }

    private String writeJson(Map<String,Object> value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException(
                    "principal provisioning fact is not serializable", invalid);
        }
    }
}
