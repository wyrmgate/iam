package io.wyrmgate.iam.api.console;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class IamOperationsApiModels {
    private IamOperationsApiModels() {}
    public record TenantResource(UUID id, String displayName, long revision, Instant createdAt, Instant updatedAt) {}
    public record WindowResource(String preset, Instant from, Instant until) {}
    public record MetricResource(String status, Long value) {}
    public record AttentionResource(MetricResource pendingApprovals, MetricResource pendingReviews,
            MetricResource finalProvisioningFailures, MetricResource failedReconciliations) {}
    public record WorkItemResource(String kind, UUID id, String subjectKind, UUID subjectId, int stageOrdinal, Instant createdAt) {}
    public record MyWorkResource(String status, String freshness, Instant asOf, String appliedType, String appliedSort,
            Long totalCount, Long approvalCount, Long reviewCount, List<WorkItemResource> items) {}
    public record AttemptBucketResource(Instant bucketStart, long succeeded, long failedRetryable, long failedFinal) {}
    public record FailureCategoryResource(String category, long count) {}
    public record ProvisioningSummaryResource(long runningTasksNow, long succeededAttempts, long retryableFailedAttempts, long finalFailedAttempts) {}
    public record ProvisioningResource(String status, String freshness, Instant asOf, ProvisioningSummaryResource summary,
            List<AttemptBucketResource> trend, List<FailureCategoryResource> failureCategories) {}
    public record ReconciliationSummaryResource(long runningNow, long failedInWindow, long completeSucceededInWindow,
            Instant lastCompleteAt, UUID lastCompleteRunId) {}
    public record ReconciliationRunResource(UUID id, UUID connectorBindingId, String state, String effectiveCompleteness,
            String failureCode, Instant startedAt, Instant completedAt) {}
    public record ReconciliationResource(String status, String freshness, Instant asOf, ReconciliationSummaryResource summary,
            List<ReconciliationRunResource> recentRuns) {}
    public record GovernanceActivityResource(String status, String freshness, Instant asOf, Long approvals, Long rejections, Long keeps, Long revokes) {}
    public record AuditEvidenceItemResource(UUID id, Instant occurredAt, String actionType, String resourceType, UUID resourceId,
            String outcome, UUID correlationId) {}
    public record SecurityEvidenceResource(String status, String freshness, Instant asOf, Long deniedCount, Long failureCount,
            List<AuditEvidenceItemResource> recent) {}
    public record EnvironmentResource(Instant asOf, MetricResource activeIdentities, MetricResource uncorrelatedPrincipals,
            MetricResource activeAccessAssignments, MetricResource applications, MetricResource activeConnectors) {}
    public record IamOperationsOverviewResource(Instant asOf, boolean partial, UUID tenantId, TenantResource tenant,
            WindowResource window, AttentionResource attention, MyWorkResource myWork, ProvisioningResource provisioning,
            ReconciliationResource reconciliation, GovernanceActivityResource myGovernanceActivity,
            SecurityEvidenceResource securityEvidence, EnvironmentResource environment) {}
}
