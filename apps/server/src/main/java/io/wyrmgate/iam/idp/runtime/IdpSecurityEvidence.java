package io.wyrmgate.iam.idp.runtime;

import io.wyrmgate.iam.audit.application.AuditRecordDraft;
import io.wyrmgate.iam.audit.application.SecurityAuditPort;
import io.wyrmgate.iam.audit.domain.AuditOutcome;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Authentication composes Audit-owned immutable evidence; never stores protocol secrets. */
public final class IdpSecurityEvidence {
    private static final Logger LOG = LoggerFactory.getLogger(IdpSecurityEvidence.class);
    private final SecurityAuditPort audit;
    private final IdGenerator ids;

    public IdpSecurityEvidence(SecurityAuditPort audit, IdGenerator ids) {
        this.audit = java.util.Objects.requireNonNull(audit);
        this.ids = java.util.Objects.requireNonNull(ids);
    }

    public void required(TenantContext tenant, UUID actor, String action, String resourceType,
            UUID resourceId, UUID correlation, UUID causation, Instant now) {
        audit.append(tenant, new AuditRecordDraft(ids.nextId(), now, actor, action, resourceType,
                resourceId, AuditOutcome.SUCCESS, correlation, causation));
    }

    public void bestEffort(TenantContext tenant, UUID actor, String action, String resourceType,
            UUID resourceId, AuditOutcome outcome, UUID correlation, UUID causation, Instant now) {
        try {
            audit.append(tenant, new AuditRecordDraft(ids.nextId(), now, actor, action, resourceType,
                    resourceId, outcome, correlation, causation));
        } catch (RuntimeException unavailable) {
            // No exception details or protocol input. Never prevent revocation.
            LOG.warn("Authentication audit append unavailable; action={} correlationId={}", action, correlation);
        }
    }
}
