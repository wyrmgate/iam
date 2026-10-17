package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;

/** Access-owned point-in-time counts used by IAM Operations. */
public interface AccessOperationsOverviewQuery {
    long activeAssignments(TenantContext tenant);
}
