package io.wyrmgate.iam.integration.application;

import io.wyrmgate.iam.integration.application.IntegrationAdministrationRepository.ConnectorBinding;
import io.wyrmgate.iam.integration.application.IntegrationAdministrationRepository.ConnectorInstance;
import io.wyrmgate.iam.integration.application.IntegrationAdministrationRepository.ConnectorWorker;
import io.wyrmgate.iam.integration.application.IntegrationAdministrationRepository.WorkerPermissionSpec;
import io.wyrmgate.iam.integration.domain.WorkerExternalSubject;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class IntegrationAdministrationCommandService {

    private final IntegrationAdministrationRepository repository;
    private final IntegrationAdministrationFactSink facts;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;

    public IntegrationAdministrationCommandService(
            IntegrationAdministrationRepository repository,
            IntegrationAdministrationFactSink facts,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.facts = Objects.requireNonNull(facts, "facts");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public ConnectorInstance createConnector(
            TenantContext tenant, String connectorType, String runtimeId, String runtimeVersion,
            long configurationVersion, Map<String,Object> configuration, String secretReference,
            Instant now, UUID correlationId) {
        validateConfiguration(configuration);
        validateConnector(connectorType, runtimeId, runtimeVersion, configurationVersion);
        return transactions.required(() -> {
            ConnectorInstance created = repository.createConnector(
                    tenant, ids.nextId(), connectorType, runtimeId, runtimeVersion,
                    configurationVersion, configuration, secretReference, now);
            facts.connectorChanged(
                    tenant, "integration.connector-created", created.id(), created.revision(),
                    now, correlationId);
            return created;
        });
    }

    public ConnectorInstance updateConnector(
            TenantContext tenant, UUID id, String runtimeId, String runtimeVersion,
            long configurationVersion, Map<String,Object> configuration, String secretReference,
            long expectedRevision, Instant now, UUID correlationId) {
        validateConfiguration(configuration);
        validateConnector("placeholder", runtimeId, runtimeVersion, configurationVersion);
        return transactions.required(() -> {
            ConnectorInstance updated = repository.updateConnector(
                    tenant, id, runtimeId, runtimeVersion, configurationVersion,
                    configuration, secretReference, expectedRevision, now);
            facts.connectorChanged(
                    tenant, "integration.connector-updated", updated.id(), updated.revision(),
                    now, correlationId);
            return updated;
        });
    }

    public ConnectorInstance disableConnector(
            TenantContext tenant, UUID id, long expectedRevision, Instant now, UUID correlationId) {
        return transactions.required(() -> {
            ConnectorInstance disabled = repository.disableConnector(
                    tenant, id, expectedRevision, now);
            facts.connectorChanged(
                    tenant, "integration.connector-disabled", disabled.id(), disabled.revision(),
                    now, correlationId);
            return disabled;
        });
    }

    public ConnectorBinding createBinding(
            TenantContext tenant, UUID connectorInstanceId, String targetKind, UUID targetId,
            String contractId, int contractVersion, boolean supportsCompletePrincipalDiscovery,
            Instant now, UUID correlationId) {
        validateBinding(targetKind, contractId, contractVersion);
        return transactions.required(() -> {
            ConnectorBinding created = repository.createBinding(
                    tenant, ids.nextId(), connectorInstanceId, targetKind, targetId,
                    contractId, contractVersion, supportsCompletePrincipalDiscovery, now);
            facts.bindingChanged(
                    tenant, "integration.connector-binding-created", created.id(), created.revision(),
                    now, correlationId);
            return created;
        });
    }

    public ConnectorBinding updateBinding(
            TenantContext tenant, UUID id, String contractId, int contractVersion,
            boolean supportsCompletePrincipalDiscovery, long expectedRevision,
            Instant now, UUID correlationId) {
        validateBinding("APPLICATION_TARGET", contractId, contractVersion);
        return transactions.required(() -> {
            ConnectorBinding updated = repository.updateBinding(
                    tenant, id, contractId, contractVersion,
                    supportsCompletePrincipalDiscovery, expectedRevision, now);
            facts.bindingChanged(
                    tenant, "integration.connector-binding-updated", updated.id(), updated.revision(),
                    now, correlationId);
            return updated;
        });
    }

    public ConnectorBinding disableBinding(
            TenantContext tenant, UUID id, long expectedRevision, Instant now, UUID correlationId) {
        return transactions.required(() -> {
            ConnectorBinding disabled = repository.disableBinding(
                    tenant, id, expectedRevision, now);
            facts.bindingChanged(
                    tenant, "integration.connector-binding-disabled", disabled.id(), disabled.revision(),
                    now, correlationId);
            return disabled;
        });
    }

    public ConnectorWorker createWorker(
            TenantContext tenant, WorkerExternalSubject subject,
            int protocolMajorMin, int protocolMajorMax,
            List<UUID> bindingScope, List<WorkerPermissionSpec> permissions,
            Instant now, UUID correlationId) {
        validateWorker(protocolMajorMin, protocolMajorMax);
        return transactions.required(() -> {
            ConnectorWorker created = repository.createWorker(
                    tenant, ids.nextId(), subject, protocolMajorMin, protocolMajorMax,
                    bindingScope, permissions, now);
            facts.workerChanged(
                    tenant, "integration.connector-worker-created", created.id(), created.revision(),
                    now, correlationId);
            return created;
        });
    }

    public ConnectorWorker updateWorker(
            TenantContext tenant, UUID id, int protocolMajorMin, int protocolMajorMax,
            List<UUID> bindingScope, List<WorkerPermissionSpec> permissions,
            long expectedRevision, Instant now, UUID correlationId) {
        validateWorker(protocolMajorMin, protocolMajorMax);
        return transactions.required(() -> {
            ConnectorWorker updated = repository.updateWorker(
                    tenant, id, protocolMajorMin, protocolMajorMax,
                    bindingScope, permissions, expectedRevision, now);
            facts.workerChanged(
                    tenant, "integration.connector-worker-updated", updated.id(), updated.revision(),
                    now, correlationId);
            return updated;
        });
    }

    public ConnectorWorker disableWorker(
            TenantContext tenant, UUID id, long expectedRevision, Instant now, UUID correlationId) {
        return transactions.required(() -> {
            ConnectorWorker disabled = repository.disableWorker(
                    tenant, id, expectedRevision, now);
            facts.workerChanged(
                    tenant, "integration.connector-worker-disabled", disabled.id(), disabled.revision(),
                    now, correlationId);
            return disabled;
        });
    }

    private static void validateConfiguration(Map<String,Object> configuration) {
        try {
            ConnectorPayloadGuard.requireSecretFree(configuration);
        } catch (WorkerProtocolException forbidden) {
            throw new IntegrationAdministrationException(
                    "secret_material_forbidden",
                    "Connector configuration contains a forbidden secret-shaped field.");
        }
    }

    private static void validateConnector(
            String connectorType, String runtimeId, String runtimeVersion, long configurationVersion) {
        requireText(connectorType, "connectorType");
        requireText(runtimeId, "runtimeId");
        requireText(runtimeVersion, "runtimeVersion");
        if (configurationVersion < 1) throw new IllegalArgumentException("configurationVersion must be positive");
    }

    private static void validateBinding(String targetKind, String contractId, int contractVersion) {
        if (!List.of("APPLICATION_TARGET", "SOURCE_SYSTEM").contains(targetKind)) {
            throw new IllegalArgumentException("unsupported targetKind");
        }
        requireText(contractId, "contractId");
        if (contractVersion < 1) throw new IllegalArgumentException("contractVersion must be positive");
    }

    private static void validateWorker(int min, int max) {
        if (min < 1 || max < min) throw new IllegalArgumentException("invalid protocol major range");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
