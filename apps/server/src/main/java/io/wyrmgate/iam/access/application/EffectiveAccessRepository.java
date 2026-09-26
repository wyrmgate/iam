package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.application.AccessQueryModels.EffectiveSupport;
import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.access.domain.EffectiveAccess;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EffectiveAccessRepository {

    void applyDirectAssignment(
            TenantContext tenant,
            AccessAssignment assignment,
            String principalConstraintKey,
            String pathHash,
            Instant computedAt);

    void removeAssignmentSupport(
            TenantContext tenant,
            UUID assignmentId,
            Instant computedAt);

    List<UUID> replaceRoleAssignmentSupport(
            TenantContext tenant,
            AccessAssignment assignment,
            String principalConstraintKey,
            List<RoleSupportPath> paths,
            Instant computedAt);

    Optional<EffectiveAccess> findCurrent(
            TenantContext tenant,
            UUID identityId,
            UUID entitlementId,
            String principalConstraintKey,
            Instant at);

    List<UUID> currentSupportingAssignmentIds(
            TenantContext tenant,
            UUID effectiveAccessId,
            Instant at);

    List<EffectiveAccess> findCurrentPage(
            TenantContext tenant,
            UUID identityId,
            UUID afterId,
            int limit,
            Instant at);

    Optional<EffectiveAccess> findCurrentById(
            TenantContext tenant,
            UUID effectiveAccessId,
            Instant at);

    List<EffectiveSupport> currentSupportEvidence(
            TenantContext tenant,
            UUID effectiveAccessId,
            Instant at);

    record RoleSupportPath(
            UUID entitlementId,
            List<UUID> roleVersionPath,
            String pathHash) {
        public RoleSupportPath {
            java.util.Objects.requireNonNull(entitlementId, "entitlementId");
            roleVersionPath = List.copyOf(roleVersionPath);
            if (roleVersionPath.isEmpty()) {
                throw new IllegalArgumentException(
                        "roleVersionPath must not be empty");
            }
            if (pathHash == null || pathHash.isBlank()) {
                throw new IllegalArgumentException(
                        "pathHash must not be blank");
            }
        }
    }
}
