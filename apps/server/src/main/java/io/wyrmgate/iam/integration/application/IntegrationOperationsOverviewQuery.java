package io.wyrmgate.iam.integration.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Integration-owned bounded operational summaries for first-party Console overview surfaces. */
public interface IntegrationOperationsOverviewQuery {
    enum BucketUnit { HOUR, DAY }
    record AttemptBucket(Instant bucketStart, long succeeded, long failedRetryable, long failedFinal) {}
    record FailureCategory(String category, long count) {}
    record Provisioning(long runningTasksNow, long succeededAttempts, long retryableFailedAttempts,
            long finalFailedAttempts, List<AttemptBucket> trend, List<FailureCategory> failureCategories) {}
    record ReconciliationRun(UUID id, UUID connectorBindingId, String state, String effectiveCompleteness,
            String failureCode, Instant startedAt, Instant completedAt) {}
    record Reconciliation(long runningNow, long failedInWindow, long completeSucceededInWindow,
            Instant lastCompleteAt, UUID lastCompleteRunId, List<ReconciliationRun> recentRuns) {}
    record SourceOperationalSummary(
            UUID sourceSystemId,
            long activeBindingCount,
            long activeConnectorCount,
            long failedReconciliationsInWindow,
            Instant lastCompleteAt) {}

    long finalProvisioningFailures(TenantContext tenant, Instant from, Instant until);
    Provisioning provisioning(TenantContext tenant, Instant from, Instant until, BucketUnit bucketUnit, int bucketCount);
    Reconciliation reconciliation(TenantContext tenant, Instant from, Instant until, int recentLimit);
    long activeConnectors(TenantContext tenant);
    List<SourceOperationalSummary> sourceOperationalSummaries(
            TenantContext tenant, Set<UUID> sourceSystemIds, Instant from, Instant until);
}
