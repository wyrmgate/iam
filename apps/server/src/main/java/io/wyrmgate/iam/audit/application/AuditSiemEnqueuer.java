package io.wyrmgate.iam.audit.application;

import io.wyrmgate.iam.audit.domain.AuditRecord;
import io.wyrmgate.iam.platform.tenant.TenantContext;

/** Best-effort post-commit technical handoff for ADR-0037 SIEM delivery. */
public interface AuditSiemEnqueuer {
    void enqueue(TenantContext tenant, AuditRecord record);
}
