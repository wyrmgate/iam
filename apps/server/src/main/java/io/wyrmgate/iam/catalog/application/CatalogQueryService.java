package io.wyrmgate.iam.catalog.application;

import io.wyrmgate.iam.catalog.application.CatalogQueryModels.ApplicationPage;
import io.wyrmgate.iam.catalog.application.CatalogQueryModels.ApplicationTargetPage;
import io.wyrmgate.iam.catalog.application.CatalogQueryModels.EntitlementPage;
import io.wyrmgate.iam.catalog.application.CatalogQueryModels.PagePosition;
import io.wyrmgate.iam.catalog.domain.Application;
import io.wyrmgate.iam.catalog.domain.ApplicationTarget;
import io.wyrmgate.iam.catalog.domain.Entitlement;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class CatalogQueryService {

    private final CatalogRepository repository;

    public CatalogQueryService(CatalogRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public Optional<Application> findApplication(TenantContext tenant, UUID id) {
        return repository.findApplication(tenant, id);
    }

    public Optional<ApplicationTarget> findTarget(TenantContext tenant, UUID id) {
        return repository.findTarget(tenant, id);
    }

    public Optional<Entitlement> findEntitlement(TenantContext tenant, UUID id) {
        return repository.findEntitlement(tenant, id);
    }

    public ApplicationPage listApplications(TenantContext tenant, PagePosition after, int limit) {
        requireLimit(limit);
        List<Application> fetched = repository.findApplicationPage(tenant, after, limit + 1);
        boolean more = fetched.size() > limit;
        List<Application> items = List.copyOf(fetched.subList(0, Math.min(limit, fetched.size())));
        PagePosition next = more
                ? new PagePosition(items.getLast().createdAt(), items.getLast().id())
                : null;
        return new ApplicationPage(items, next);
    }

    public ApplicationTargetPage listTargets(
            TenantContext tenant, UUID applicationId, PagePosition after, int limit) {
        requireLimit(limit);
        List<ApplicationTarget> fetched =
                repository.findTargetPage(tenant, applicationId, after, limit + 1);
        boolean more = fetched.size() > limit;
        List<ApplicationTarget> items =
                List.copyOf(fetched.subList(0, Math.min(limit, fetched.size())));
        PagePosition next = more
                ? new PagePosition(items.getLast().createdAt(), items.getLast().id())
                : null;
        return new ApplicationTargetPage(items, next);
    }

    public EntitlementPage listEntitlements(
            TenantContext tenant, UUID applicationId, PagePosition after, int limit) {
        requireLimit(limit);
        List<Entitlement> fetched =
                repository.findEntitlementPage(tenant, applicationId, after, limit + 1);
        boolean more = fetched.size() > limit;
        List<Entitlement> items =
                List.copyOf(fetched.subList(0, Math.min(limit, fetched.size())));
        PagePosition next = more
                ? new PagePosition(items.getLast().createdAt(), items.getLast().id())
                : null;
        return new EntitlementPage(items, next);
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
    }
}
