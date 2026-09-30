package io.wyrmgate.iam.audit.application;

import io.wyrmgate.iam.audit.domain.AuditRecord;
import io.wyrmgate.iam.platform.tenant.TenantContext;

/** Cross-capability semantic command for appending immutable security/governance audit evidence. */
public interface SecurityAuditPort {
    AuditRecord append(TenantContext tenant, AuditRecordDraft draft);
}
