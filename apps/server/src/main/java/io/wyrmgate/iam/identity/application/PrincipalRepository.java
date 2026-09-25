package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.identity.domain.Principal;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Identity-owned authoritative Principal persistence port. */
public interface PrincipalRepository {

    void insert(TenantContext tenant, Principal principal);

    Optional<Principal> findById(TenantContext tenant, UUID principalId);

    Optional<Principal> findByTargetAndNativeKey(
            TenantContext tenant, UUID applicationTargetId, String nativePrincipalKey);

    Principal correlate(
            TenantContext tenant,
            UUID principalId,
            UUID identityId,
            long expectedRevision,
            Instant now);
}
