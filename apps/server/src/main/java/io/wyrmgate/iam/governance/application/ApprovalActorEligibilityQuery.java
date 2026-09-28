package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.UUID;

public interface ApprovalActorEligibilityQuery {

    boolean isEligibleApprover(
            TenantContext tenant,
            UUID identityId);
}
