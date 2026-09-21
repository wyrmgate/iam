package io.wyrmgate.iam.integration.application;

import io.wyrmgate.iam.integration.application.ConnectorWorkRepository.ProvisioningCandidate;
import io.wyrmgate.iam.integration.application.ConnectorWorkRepository.ReconciliationCandidate;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface ConnectorExecutionRepository {

    List<ProvisioningCandidate> lockLocalProvisioningCandidates(
            String runtimeId,
            String runtimeVersion,
            String contractId,
            int contractVersion,
            int limit,
            Instant now);

    List<ReconciliationCandidate> lockLocalReconciliationCandidates(
            String runtimeId,
            String runtimeVersion,
            String contractId,
            int contractVersion,
            int limit,
            Instant now);

    Optional<ExecutionConfiguration> findExecutionConfiguration(
            TenantContext tenant,
            UUID connectorBindingId);

    int nextObservationSequence(TenantContext tenant, UUID reconciliationRunId);

    record ExecutionConfiguration(
            UUID connectorBindingId,
            UUID connectorInstanceId,
            String connectorType,
            String runtimeId,
            String runtimeVersion,
            long configurationVersion,
            Map<String,Object> configuration,
            String secretReference,
            String contractId,
            int contractVersion,
            boolean supportsCompletePrincipalDiscovery,
            boolean supportsCompleteEntitlementDiscovery,
            boolean supportsCompleteGrantDiscovery) {}
}
