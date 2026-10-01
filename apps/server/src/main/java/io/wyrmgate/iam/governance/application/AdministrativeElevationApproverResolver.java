package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.application.ApprovalModels.PlanSpec;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.UUID;

/** Typed resolver for administrative-elevation approvers; no generic workflow language. */
public interface AdministrativeElevationApproverResolver {
    PlanSpec resolve(TenantContext tenant, UUID elevationId, UUID initiatorIdentityId);
}
