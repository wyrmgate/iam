package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.administration.domain.AdministrativeGrant;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.List;
import java.util.UUID;

/** Administration-owned persistence port used to evaluate direct grants. */
public interface AdministrativeAuthorizationRepository {

    List<AdministrativeGrant> findCandidateGrants(
            TenantContext tenant,
            UUID actorIdentityId,
            AdministrativePermission permission);
}
