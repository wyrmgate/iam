package io.wyrmgate.iam.api.integration;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class IntegrationAdminApiModels {
    private IntegrationAdminApiModels() {}

    record ConnectorCreateRequest(
            String connectorType,
            String runtimeId,
            String runtimeVersion,
            long configurationVersion,
            Map<String,Object> configuration,
            String secretReference) {}

    record ConnectorUpdateRequest(
            String runtimeId,
            String runtimeVersion,
            long configurationVersion,
            Map<String,Object> configuration,
            String secretReference) {}

    record ConnectorResource(
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

    record BindingCreateRequest(
            UUID connectorInstanceId,
            String targetKind,
            UUID targetId,
            String contractId,
            int contractVersion,
            boolean supportsCompletePrincipalDiscovery,
            boolean supportsCompleteEntitlementDiscovery,
            boolean supportsCompleteGrantDiscovery) {}

    record BindingUpdateRequest(
            String contractId,
            int contractVersion,
            boolean supportsCompletePrincipalDiscovery,
            boolean supportsCompleteEntitlementDiscovery,
            boolean supportsCompleteGrantDiscovery) {}

    record BindingResource(
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

    record EntitlementMappingCreateRequest(
            String providerStableId,
            UUID entitlementId) {}

    record EntitlementMappingResource(
            UUID id,
            UUID connectorBindingId,
            String providerStableId,
            UUID entitlementId,
            String lifecycleState,
            long revision,
            Instant createdAt,
            Instant updatedAt,
            Instant retiredAt) {}

    record WorkerCreateRequest(
            String issuer,
            String subject,
            int protocolMajorMin,
            int protocolMajorMax,
            List<UUID> bindingScope,
            List<WorkerPermissionRequest> permissions) {}

    record WorkerUpdateRequest(
            int protocolMajorMin,
            int protocolMajorMax,
            List<UUID> bindingScope,
            List<WorkerPermissionRequest> permissions) {}

    record WorkerPermissionRequest(
            String runtimeId,
            String runtimeVersion,
            String capability,
            String contractId,
            int contractVersion) {}

    record WorkerResource(
            UUID id,
            String issuer,
            String subject,
            String state,
            int protocolMajorMin,
            int protocolMajorMax,
            List<UUID> bindingScope,
            List<WorkerPermissionRequest> permissions,
            long revision,
            Instant createdAt,
            Instant updatedAt) {}

    record ErrorResponse(
            String code,
            String message,
            UUID correlationId) {}
}
