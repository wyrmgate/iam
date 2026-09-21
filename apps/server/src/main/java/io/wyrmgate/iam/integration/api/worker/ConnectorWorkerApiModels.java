package io.wyrmgate.iam.integration.api.worker;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ConnectorWorkerApiModels {
    private ConnectorWorkerApiModels() {}

    record SessionRequest(
            List<Integer> supportedProtocolMajors,
            String workerInstanceId,
            List<RuntimeAdvertisement> runtimes) {}

    record RuntimeAdvertisement(
            String runtimeId,
            String runtimeVersion,
            List<String> capabilities,
            List<ContractSupport> contractSchemas) {}

    record ContractSupport(String contractId, List<Integer> versions) {}

    record SessionResponse(
            UUID sessionId,
            int selectedProtocolMajor,
            int maxClaimSize,
            int maxLongPollSeconds,
            int maxObservationBatchSize,
            int leaseDurationSeconds,
            List<RuntimeAdvertisement> acceptedRuntimes) {}

    record ClaimRequest(int maxItems, int waitSeconds) {}

    record ClaimResponse(List<LeasedWorkItem> items) {}

    record LeasedWorkItem(
            UUID workId,
            UUID operationId,
            LeaseToken lease,
            UUID tenantId,
            UUID connectorBindingId,
            String workKind,
            String contractId,
            int contractVersion,
            Long desiredRevision,
            String checkpoint,
            String idempotencyKey,
            UUID correlationId,
            UUID causationId,
            Map<String,Object> payload) {}

    record LeaseToken(UUID leaseId, long leaseEpoch, Instant leaseExpiresAt) {}

    record ObservationBatch(
            UUID leaseId,
            long leaseEpoch,
            UUID batchId,
            int sequence,
            List<Observation> observations) {}

    record Observation(
            String objectClass,
            String providerStableId,
            String providerVersion,
            Map<String,Object> observedState) {}

    record WorkCompletion(
            UUID leaseId,
            long leaseEpoch,
            String outcome,
            NormalizedResult result,
            String discoveryCoverage,
            String nextCheckpoint) {}

    record NormalizedResult(
            String failureCategory,
            String providerErrorCode,
            String providerRequestId,
            Integer retryAfterSeconds,
            String providerObjectId,
            String providerVersion,
            Map<String,Object> metadata) {}
}
