package io.wyrmgate.iam.integration.domain;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record LeasedConnectorWork(
        UUID workId,
        UUID operationId,
        WorkerLease lease,
        TenantContext tenant,
        UUID connectorBindingId,
        WorkKind workKind,
        String contractId,
        int contractVersion,
        Long desiredRevision,
        String checkpoint,
        String idempotencyKey,
        UUID correlationId,
        UUID causationId,
        Map<String, Object> payload,
        ExecutionContext executionContext) {

    /** Non-secret execution configuration pin; workers must resolve this exact version. */
    public record ExecutionContext(String runtimeId,String runtimeVersion,long configurationVersion,long bindingRevision,long connectorRevision){
        public boolean matches(io.wyrmgate.iam.integration.application.ConnectorExecutionRepository.ExecutionConfiguration c){
            return runtimeId.equals(c.runtimeId())&&runtimeVersion.equals(c.runtimeVersion())&&configurationVersion==c.configurationVersion()
                &&bindingRevision==c.bindingRevision()&&connectorRevision==c.connectorRevision();
        }
    }

    public LeasedConnectorWork {
        Objects.requireNonNull(workId, "workId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(connectorBindingId, "connectorBindingId");
        Objects.requireNonNull(workKind, "workKind");
        if (contractId == null || contractId.isBlank()) throw new IllegalArgumentException("contractId must not be blank");
        if (contractVersion < 1) throw new IllegalArgumentException("contractVersion must be positive");
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new IllegalArgumentException("idempotencyKey must not be blank");
        Objects.requireNonNull(correlationId, "correlationId");
        payload = Map.copyOf(Objects.requireNonNull(payload, "payload"));
    }
}
