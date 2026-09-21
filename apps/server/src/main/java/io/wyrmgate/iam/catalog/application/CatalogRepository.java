package io.wyrmgate.iam.catalog.application;

import io.wyrmgate.iam.catalog.application.CatalogQueryModels.PagePosition;
import io.wyrmgate.iam.catalog.domain.Application;
import io.wyrmgate.iam.catalog.domain.ApplicationTarget;
import io.wyrmgate.iam.catalog.domain.Entitlement;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Catalog-owned authoritative persistence/query port. */
public interface CatalogRepository {

    void insertApplication(TenantContext tenant, Application application);
    Optional<Application> findApplication(TenantContext tenant, UUID applicationId);
    List<Application> findApplicationPage(TenantContext tenant, PagePosition after, int limit);
    Application updateApplicationName(
            TenantContext tenant, UUID applicationId, String name, long expectedRevision, Instant now);
    Application retireApplication(
            TenantContext tenant, UUID applicationId, long expectedRevision, Instant now);

    void insertTarget(TenantContext tenant, ApplicationTarget target);
    Optional<ApplicationTarget> findTarget(TenantContext tenant, UUID targetId);
    List<ApplicationTarget> findTargetPage(
            TenantContext tenant, UUID applicationId, PagePosition after, int limit);
    ApplicationTarget retireTarget(
            TenantContext tenant, UUID targetId, long expectedRevision, Instant now);

    void insertEntitlement(TenantContext tenant, Entitlement entitlement);
    Optional<Entitlement> findEntitlement(TenantContext tenant, UUID entitlementId);
    List<Entitlement> findEntitlementPage(
            TenantContext tenant, UUID applicationId, PagePosition after, int limit);
    Entitlement retireEntitlement(
            TenantContext tenant, UUID entitlementId, long expectedRevision, Instant now);
}
