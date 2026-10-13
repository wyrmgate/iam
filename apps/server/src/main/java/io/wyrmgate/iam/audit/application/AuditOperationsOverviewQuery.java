package io.wyrmgate.iam.audit.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Audit-owned bounded evidence summary for IAM Operations. */
public interface AuditOperationsOverviewQuery {
    record EvidenceItem(UUID id, Instant occurredAt, String actionType, String resourceType, UUID resourceId,
            String outcome, UUID correlationId) {}
    record Evidence(long deniedCount, long failureCount, List<EvidenceItem> recent) {}
    Evidence evidence(TenantContext tenant, Instant from, Instant until, int recentLimit);
}
