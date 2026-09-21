package io.wyrmgate.iam.integration.application;

import io.wyrmgate.iam.integration.domain.LeasedConnectorWork;
import io.wyrmgate.iam.integration.domain.ReconciliationCompleteness;
import io.wyrmgate.iam.integration.domain.WorkerLease;
import io.wyrmgate.iam.integration.domain.WorkerSession;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

public interface ConnectorWorkRepository {
    List<ProvisioningCandidate> lockProvisioningCandidates(
            WorkerSession session, int limit, Instant now);
    List<ReconciliationCandidate> lockReconciliationCandidates(
            WorkerSession session, int limit, Instant now);
    void supersedeProvisioning(UUID tenantId, UUID taskId, long expectedRevision, Instant now);
    LeasedConnectorWork leaseProvisioning(
            WorkerSession session, ProvisioningCandidate candidate, Instant now, Duration duration);
    LeasedConnectorWork leaseReconciliation(
            WorkerSession session, ReconciliationCandidate candidate, Instant now, Duration duration);
    WorkerLease renewLease(
            WorkerSession session, UUID workId, UUID leaseId, long leaseEpoch,
            Instant now, Duration duration);
    ObservationBatchResult appendProviderObservations(
            WorkerSession session,
            UUID workId,
            UUID leaseId,
            long leaseEpoch,
            UUID batchId,
            int sequence,
            String requestFingerprint,
            List<ProviderObservation> observations,
            Instant now);
    CompletionResult complete(
            WorkerSession session,
            UUID workId,
            UUID leaseId,
            long leaseEpoch,
            WorkCompletion completion,
            String completionFingerprint,
            Instant now);

    record ProvisioningCandidate(
            UUID taskId,
            UUID operationId,
            UUID tenantId,
            UUID connectorBindingId,
            String operationType,
            String subjectKind,
            UUID subjectId,
            long desiredRevision,
            String contractId,
            int contractVersion,
            String idempotencyKey,
            UUID correlationId,
            UUID causationId,
            Map<String,Object> payload) {}

    record ReconciliationCandidate(
            UUID runId,
            UUID operationId,
            UUID tenantId,
            UUID connectorBindingId,
            String contractId,
            int contractVersion,
            String checkpoint,
            UUID correlationId,
            UUID causationId,
            Map<String,Object> payload) {}

    record ProviderObservation(
            String objectClass,
            String providerStableId,
            String providerVersion,
            Map<String,Object> observedState) {}

    record WorkCompletion(
            String outcome,
            String failureCategory,
            String providerErrorCode,
            String providerRequestId,
            Integer retryAfterSeconds,
            String providerObjectId,
            String providerVersion,
            Map<String,Object> metadata,
            ReconciliationCompleteness discoveryCoverage,
            String nextCheckpoint) {}

    enum ObservationBatchResult { ACCEPTED, REPLAY }

    enum CompletionResult { ACCEPTED, REPLAY }
}
