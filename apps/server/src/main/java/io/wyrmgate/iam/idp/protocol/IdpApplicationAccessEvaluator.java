package io.wyrmgate.iam.idp.protocol;

import io.wyrmgate.iam.access.application.EffectiveAccessQuery;
import io.wyrmgate.iam.catalog.application.CatalogQueryModels.PagePosition;
import io.wyrmgate.iam.catalog.application.CatalogQueryService;
import io.wyrmgate.iam.catalog.domain.CatalogLifecycleState;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Composed IdP read model for the optional governed-application access gate.
 *
 * <p>Catalog remains authoritative for which entitlements belong to the Application and Access
 * remains authoritative for current effective access. This adapter never joins or mutates their
 * persistence directly.</p>
 */
public final class IdpApplicationAccessEvaluator implements IdpApplicationAccessQuery {

    private static final int PAGE_SIZE = 200;

    private final CatalogQueryService catalog;
    private final EffectiveAccessQuery effectiveAccess;

    public IdpApplicationAccessEvaluator(
            CatalogQueryService catalog,
            EffectiveAccessQuery effectiveAccess) {
        this.catalog = catalog;
        this.effectiveAccess = effectiveAccess;
    }

    @Override
    public boolean hasCurrentAccess(
            TenantContext tenant,
            UUID identityId,
            UUID applicationId,
            Instant at) {
        PagePosition after = null;
        do {
            var page = catalog.listEntitlements(tenant, applicationId, after, PAGE_SIZE);
            Set<UUID> activeEntitlements = new LinkedHashSet<>();
            page.items().stream()
                    .filter(item -> item.lifecycleState() == CatalogLifecycleState.ACTIVE)
                    .map(item -> item.id())
                    .forEach(activeEntitlements::add);
            if (!activeEntitlements.isEmpty()
                    && !effectiveAccess.currentEntitlementIds(
                                    tenant, identityId, activeEntitlements, at)
                            .isEmpty()) {
                return true;
            }
            after = page.nextPosition();
        } while (after != null);
        return false;
    }
}
