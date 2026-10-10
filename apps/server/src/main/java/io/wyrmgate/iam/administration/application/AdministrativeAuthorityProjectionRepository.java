package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.administration.domain.AdministrativeBreakGlassOperation;
import io.wyrmgate.iam.administration.domain.AdministrativeElevation;
import io.wyrmgate.iam.administration.domain.AdministrativeGrant;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Administration-owned read port for deriving the current actor authority projection. */
public interface AdministrativeAuthorityProjectionRepository {

    record GrantCandidate(AdministrativePermission permission, AdministrativeGrant grant) {
        public GrantCandidate {
            Objects.requireNonNull(permission, "permission");
            Objects.requireNonNull(grant, "grant");
        }
    }

    record DelegationCandidate(
            AdministrativePermission permission,
            AdministrativeDelegatedAuthorityCandidate authority) {
        public DelegationCandidate {
            Objects.requireNonNull(permission, "permission");
            Objects.requireNonNull(authority, "authority");
        }
    }

    record ElevationCandidate(
            AdministrativePermission permission,
            AdministrativeElevation elevation) {
        public ElevationCandidate {
            Objects.requireNonNull(permission, "permission");
            Objects.requireNonNull(elevation, "elevation");
        }
    }

    record BreakGlassCandidate(
            AdministrativePermission permission,
            AdministrativeBreakGlassOperation operation) {
        public BreakGlassCandidate {
            Objects.requireNonNull(permission, "permission");
            Objects.requireNonNull(operation, "operation");
        }
    }

    List<GrantCandidate> findGrantCandidates(TenantContext tenant, UUID actorIdentityId);

    List<DelegationCandidate> findDelegationCandidates(TenantContext tenant, UUID actorIdentityId);

    List<ElevationCandidate> findElevationCandidates(TenantContext tenant, UUID actorIdentityId);

    List<BreakGlassCandidate> findBreakGlassCandidates(TenantContext tenant, UUID actorIdentityId);
}
