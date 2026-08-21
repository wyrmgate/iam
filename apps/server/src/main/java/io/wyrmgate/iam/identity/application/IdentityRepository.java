package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.identity.domain.Identity;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Identity-owned persistence port. */
public interface IdentityRepository {

    void insert(TenantContext tenant, Identity identity);

    Optional<Identity> findById(TenantContext tenant, UUID identityId);

    Identity updateDisplayName(
            TenantContext tenant,
            UUID identityId,
            String displayName,
            long expectedRevision,
            Instant now);
}
