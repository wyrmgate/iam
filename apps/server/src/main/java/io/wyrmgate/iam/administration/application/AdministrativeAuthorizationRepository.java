package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.administration.domain.AdministrativeBreakGlassOperation;
import io.wyrmgate.iam.administration.domain.AdministrativeElevation;
import io.wyrmgate.iam.administration.domain.AdministrativeGrant;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.List;
import java.util.UUID;

/** Administration-owned persistence port used to evaluate direct and delegated authority. */
public interface AdministrativeAuthorizationRepository {

    List<AdministrativeGrant> findCandidateGrants(
            TenantContext tenant,
            UUID actorIdentityId,
            AdministrativePermission permission);


    default List<AdministrativeDelegatedAuthorityCandidate> findCandidateDelegations(
            TenantContext tenant,
            UUID actorIdentityId,
            AdministrativePermission permission) {
        return List.of();
    }


    default List<AdministrativeElevation> findCandidateElevations(
            TenantContext tenant,
            UUID actorIdentityId,
            AdministrativePermission permission) {
        return List.of();
    }

    default List<AdministrativeBreakGlassOperation> findCandidateBreakGlassOperations(
            TenantContext tenant,
            UUID actorIdentityId,
            AdministrativePermission permission) {
        return List.of();
    }
}
