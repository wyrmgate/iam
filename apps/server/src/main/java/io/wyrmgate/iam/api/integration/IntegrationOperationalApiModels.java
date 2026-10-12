package io.wyrmgate.iam.api.integration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class IntegrationOperationalApiModels {
    private IntegrationOperationalApiModels() {}

    record ProvisioningJobResource(
            UUID id,
            UUID connectorBindingId,
            String state,
            long revision,
            UUID correlationId,
            UUID causationId,
            Instant createdAt,
            Instant updatedAt) {}

    record ProvisioningJobPage(
            List<ProvisioningJobResource> items,
            String nextCursor) {}

    record ProvisioningTaskResource(
            UUID id,
            UUID provisioningJobId,
            UUID operationId,
            String operationType,
            String subjectKind,
            UUID subjectId,
            long desiredRevision,
            String contractId,
            int contractVersion,
            String state,
            int attemptCount,
            Instant nextAttemptAt,
            String failureCode,
            long revision,
            UUID correlationId,
            UUID causationId,
            Instant createdAt,
            Instant updatedAt) {}

    record ProvisioningTaskPage(
            List<ProvisioningTaskResource> items,
            String nextCursor) {}

    record ProvisioningAttemptResource(
            UUID id,
            UUID taskId,
            int attemptNumber,
            Instant startedAt,
            Instant completedAt,
            String outcome,
            String failureCategory,
            UUID correlationId,
            UUID causationId) {}

    record ProvisioningAttemptPage(
            List<ProvisioningAttemptResource> items,
            String nextCursor) {}

    record ReconciliationRunResource(
            UUID id,
            UUID connectorBindingId,
            UUID operationId,
            String scopeObjectClass,
            String state,
            String reportedCoverage,
            String effectiveCompleteness,
            long configurationVersion,
            String runtimeId,
            String runtimeVersion,
            String contractId,
            int contractVersion,
            Instant nextAttemptAt,
            String failureCode,
            UUID correlationId,
            UUID causationId,
            long revision,
            Instant startedAt,
            Instant completedAt,
            Instant createdAt,
            Instant updatedAt) {}

    record ReconciliationRunPage(
            List<ReconciliationRunResource> items,
            String nextCursor) {}
}
