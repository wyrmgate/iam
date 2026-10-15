package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Identity-owned bounded presentation query for Access operator views.
 *
 * <p>This is a read-only semantic collaboration surface. It exposes only human labels for already
 * authorized stable references and does not transfer Identity or Principal authority to Access.</p>
 */
public interface IdentityAccessPresentationQuery {

    Labels labels(TenantContext tenant, Set<UUID> identityIds, Set<UUID> principalIds);

    record Labels(Map<UUID, String> identities, Map<UUID, String> principals) {
        public Labels {
            identities = Map.copyOf(identities);
            principals = Map.copyOf(principals);
        }
    }
}
