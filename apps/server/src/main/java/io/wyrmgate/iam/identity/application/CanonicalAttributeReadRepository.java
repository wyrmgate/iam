package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.identity.domain.CanonicalAttributeState;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Optional;
import java.util.UUID;

/** Non-locking read port for current materialized canonical attribute state. */
public interface CanonicalAttributeReadRepository {

    Optional<CanonicalAttributeState> findState(
            TenantContext tenant,
            UUID identityId,
            UUID attributeDefinitionId);
}
