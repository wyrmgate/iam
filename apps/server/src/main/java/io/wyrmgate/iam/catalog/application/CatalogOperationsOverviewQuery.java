package io.wyrmgate.iam.catalog.application;
import io.wyrmgate.iam.platform.tenant.TenantContext;
/** Catalog-owned point-in-time counts used by IAM Operations. */
public interface CatalogOperationsOverviewQuery { long activeApplications(TenantContext tenant); }
