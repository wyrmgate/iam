package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Governance-owned bounded read projection for the first-party IAM Operations surface. */
public interface GovernanceOperationsOverviewQuery {
    enum WorkType { ALL, APPROVAL, REVIEW }
    enum WorkSort { OLDEST, NEWEST }

    record DecisionTotals(long approvals, long rejections, long keeps, long revokes) {}

    record WorkItem(
            String kind,
            UUID id,
            String subjectKind,
            UUID subjectId,
            int stageOrdinal,
            Instant createdAt) {}

    record Snapshot(
            long pendingApprovals,
            long pendingReviews,
            DecisionTotals decisions,
            List<WorkItem> workItems) {}

    Snapshot snapshot(
            TenantContext tenant,
            UUID actorIdentityId,
            Instant from,
            Instant until,
            WorkType workType,
            WorkSort workSort,
            int limit);
}
