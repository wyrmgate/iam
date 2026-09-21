package io.wyrmgate.iam.integration.application;

import io.wyrmgate.iam.integration.domain.WorkerCapability;
import io.wyrmgate.iam.integration.domain.WorkerExternalSubject;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface IntegrationAdministrationRepository {

    ConnectorInstance createConnector(
            TenantContext tenant,
            UUID id,
            String connectorType,
            String runtimeId,
            String runtimeVersion,
            long configurationVersion,
            Map<String,Object> configuration,
            String secretReference,
            Instant now);

    Optional<ConnectorInstance> findConnector(TenantContext tenant, UUID id);

    ConnectorInstance updateConnector(
            TenantContext tenant,
            UUID id,
            String runtimeId,
            String runtimeVersion,
            long configurationVersion,
            Map<String,Object> configuration,
            String secretReference,
            long expectedRevision,
            Instant now);

    ConnectorInstance disableConnector(
            TenantContext tenant,
            UUID id,
            long expectedRevision,
            Instant now);

    ConnectorBinding createBinding(
            TenantContext tenant,
            UUID id,
            UUID connectorInstanceId,
            String targetKind,
            UUID targetId,
            String contractId,
            int contractVersion,
            boolean supportsCompletePrincipalDiscovery,
            boolean supportsCompleteEntitlementDiscovery,
            boolean supportsCompleteGrantDiscovery,
            Instant now);

    Optional<ConnectorBinding> findBinding(TenantContext tenant, UUID id);

    ConnectorBinding updateBinding(
            TenantContext tenant,
            UUID id,
            String contractId,
            int contractVersion,
            boolean supportsCompletePrincipalDiscovery,
            boolean supportsCompleteEntitlementDiscovery,
            boolean supportsCompleteGrantDiscovery,
            long expectedRevision,
            Instant now);

    ConnectorBinding disableBinding(
            TenantContext tenant,
            UUID id,
            long expectedRevision,
            Instant now);

    ConnectorWorker createWorker(
            TenantContext tenant,
            UUID id,
            WorkerExternalSubject externalSubject,
            int protocolMajorMin,
            int protocolMajorMax,
            List<UUID> bindingScope,
            List<WorkerPermissionSpec> permissions,
            Instant now);

    Optional<ConnectorWorker> findWorker(TenantContext tenant, UUID id);

    ConnectorWorker updateWorker(
            TenantContext tenant,
            UUID id,
            int protocolMajorMin,
            int protocolMajorMax,
            List<UUID> bindingScope,
            List<WorkerPermissionSpec> permissions,
            long expectedRevision,
            Instant now);

    ConnectorWorker disableWorker(
            TenantContext tenant,
            UUID id,
            long expectedRevision,
            Instant now);

    record ConnectorInstance(
            UUID id,
            String connectorType,
            String runtimeId,
            String runtimeVersion,
            long configurationVersion,
            Map<String,Object> configuration,
            boolean secretConfigured,
            String lifecycleState,
            long revision,
            Instant createdAt,
            Instant updatedAt) {}

    record ConnectorBinding(
            UUID id,
            UUID connectorInstanceId,
            String targetKind,
            UUID targetId,
            String contractId,
            int contractVersion,
            boolean supportsCompletePrincipalDiscovery,
            boolean supportsCompleteEntitlementDiscovery,
            boolean supportsCompleteGrantDiscovery,
            String lifecycleState,
            long revision,
            Instant createdAt,
            Instant updatedAt) {}

    record ConnectorWorker(
            UUID id,
            String issuer,
            String subject,
            String state,
            int protocolMajorMin,
            int protocolMajorMax,
            List<UUID> bindingScope,
            List<WorkerPermissionSpec> permissions,
            long revision,
            Instant createdAt,
            Instant updatedAt) {}

    record WorkerPermissionSpec(
            String runtimeId,
            String runtimeVersion,
            WorkerCapability capability,
            String contractId,
            int contractVersion) {}
}
