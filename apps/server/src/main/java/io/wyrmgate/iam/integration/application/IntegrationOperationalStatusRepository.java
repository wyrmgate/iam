package io.wyrmgate.iam.integration.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Integration-owned read port for bounded public operational status projections. */
public interface IntegrationOperationalStatusRepository {

    record PagePosition(Instant sortAt, UUID id) {}

    record ProvisioningJobStatus(
            UUID id,
            UUID connectorBindingId,
            String state,
            long revision,
            UUID correlationId,
            UUID causationId,
            Instant createdAt,
            Instant updatedAt) {}

    record ProvisioningTaskStatus(
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

    record ProvisioningAttemptStatus(
            UUID id,
            UUID taskId,
            int attemptNumber,
            Instant startedAt,
            Instant completedAt,
            String outcome,
            String failureCategory,
            UUID correlationId,
            UUID causationId) {}

    record ReconciliationRunStatus(
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

    Optional<ProvisioningJobStatus> findProvisioningJob(TenantContext tenant, UUID id);

    List<ProvisioningJobStatus> findProvisioningJobPage(
            TenantContext tenant, PagePosition after, int limit);

    Optional<ProvisioningTaskStatus> findProvisioningTask(TenantContext tenant, UUID id);

    List<ProvisioningTaskStatus> findProvisioningTaskPage(
            TenantContext tenant, PagePosition after, int limit);

    Optional<ProvisioningAttemptStatus> findProvisioningAttempt(TenantContext tenant, UUID id);

    List<ProvisioningAttemptStatus> findProvisioningAttemptPage(
            TenantContext tenant, UUID taskId, PagePosition after, int limit);

    Optional<ReconciliationRunStatus> findReconciliationRun(TenantContext tenant, UUID id);

    List<ReconciliationRunStatus> findReconciliationRunPage(
            TenantContext tenant, PagePosition after, int limit);
}
