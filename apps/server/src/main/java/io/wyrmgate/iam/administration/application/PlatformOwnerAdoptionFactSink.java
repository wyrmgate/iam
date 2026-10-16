package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;

/** Transactional fact emitted only after explicit legacy Platform Owner adoption succeeds. */
@FunctionalInterface
public interface PlatformOwnerAdoptionFactSink {

    void adopted(
            TenantContext tenant,
            UUID identityId,
            UUID principalId,
            UUID tenantOwnerAuthorityId,
            UUID platformOwnerAuthorityId,
            Instant adoptedAt,
            UUID correlationId);
}
