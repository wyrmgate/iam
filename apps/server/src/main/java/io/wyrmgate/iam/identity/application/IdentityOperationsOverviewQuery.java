package io.wyrmgate.iam.identity.application;
import io.wyrmgate.iam.platform.tenant.TenantContext;
/** Identity-owned point-in-time counts used by IAM Operations. */
public interface IdentityOperationsOverviewQuery {
    long activeIdentities(TenantContext tenant);
    long uncorrelatedPrincipals(TenantContext tenant);
}
