package io.wyrmgate.iam.access.application;
import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;

/** Mandatory current SoD decision for manual creation and resume. */
public interface AccessPrivilegeIncreaseGuard {
    void requireAllowed(TenantContext tenant, UUID identityId, AccessAssignment.TargetKind kind,
                        UUID targetId, String fingerprint, Instant from, Instant until, Instant at);
}
