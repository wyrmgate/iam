package io.wyrmgate.iam.governance.application;
import io.wyrmgate.iam.governance.application.ApprovalModels.ApprovalCase;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.UUID;

/** Current authority check for a new immutable human decision, not a historical replay. */
@FunctionalInterface
public interface ApprovalActorAuthority {
    void requireCurrent(TenantContext tenant, ApprovalCase approvalCase, UUID actor);
}
