package io.wyrmgate.iam.audit.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Audit-owned bounded evidence summaries for first-party Console overview surfaces. */
public interface AuditOperationsOverviewQuery {
    record EvidenceItem(UUID id, Instant occurredAt, String actionType, String resourceType, UUID resourceId,
            String outcome, UUID correlationId) {}
    record Evidence(long deniedCount, long failureCount, List<EvidenceItem> recent) {}
    record ActivityItem(
            UUID id,
            Instant occurredAt,
            UUID actorId,
            String actorDisplayLabel,
            String actionType,
            String resourceType,
            UUID resourceId,
            String resourceDisplayLabel,
            String outcome) {}

    Evidence evidence(TenantContext tenant, Instant from, Instant until, int recentLimit);

    List<ActivityItem> recentActivity(TenantContext tenant, Instant from, Instant until, int recentLimit);
}
