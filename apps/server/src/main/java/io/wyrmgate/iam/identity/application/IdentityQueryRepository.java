package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.identity.application.IdentityQueryModels.CanonicalAttributeDefinitionEntry;
import io.wyrmgate.iam.identity.application.IdentityQueryModels.CanonicalAttributePagePosition;
import io.wyrmgate.iam.identity.application.IdentityQueryModels.IdentityListFilter;
import io.wyrmgate.iam.identity.application.IdentityQueryModels.IdentityPagePosition;
import io.wyrmgate.iam.identity.domain.Identity;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.List;

/** Identity-owned bounded query port used by public authoritative read surfaces. */
public interface IdentityQueryRepository {

    List<Identity> findIdentityPage(TenantContext tenant, IdentityPagePosition after, int limit);

    default List<Identity> findIdentityPage(
            TenantContext tenant,
            IdentityListFilter filter,
            IdentityPagePosition after,
            int limit) {
        if (filter == null || filter.isEmpty()) {
            return findIdentityPage(tenant, after, limit);
        }
        throw new UnsupportedOperationException("filtered Identity collection query is not implemented by this adapter");
    }

    List<CanonicalAttributeDefinitionEntry> findActiveCanonicalDefinitionPage(
            TenantContext tenant,
            CanonicalAttributePagePosition after,
            int limit);
}
