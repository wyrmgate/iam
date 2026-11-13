package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.application.AccessQueryModels.AssignmentFilter;
import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccessAssignmentRepository {

    void insert(TenantContext tenant, AccessAssignment assignment);

    Optional<AccessAssignment> findById(TenantContext tenant, UUID assignmentId);

    default Optional<AccessAssignment> findByProvenance(
            TenantContext tenant,
            AccessAssignment.ProvenanceKind provenanceKind,
            UUID provenanceRefId) { return Optional.empty(); }

    /** Bounded, immutable-order scan for a current Role projection repair. */
    default List<AccessAssignment> findRoleRefreshPage(
            TenantContext tenant, UUID roleId, Instant snapshotAt,
            Instant afterCreatedAt, UUID afterId, int limit) {
        throw new UnsupportedOperationException("Role projection refresh paging is not implemented");
    }

    default Optional<AccessAssignment> findCurrentLifecyclePolicyAssignment(
            TenantContext tenant, UUID identityId, UUID ruleId) { return Optional.empty(); }

    default List<AccessAssignment> findCurrentLifecyclePolicyAssignments(
            TenantContext tenant, UUID identityId) { return List.of(); }

    default List<AccessAssignment> findPage(
            TenantContext tenant, Instant afterCreatedAt, UUID afterId, int limit) { return List.of(); }

    default List<AccessAssignment> findPageByIdentity(
            TenantContext tenant, UUID identityId, Instant afterCreatedAt, UUID afterId, int limit) { return List.of(); }

    default List<AccessAssignment> findPageFiltered(
            TenantContext tenant,
            AssignmentFilter filter,
            Instant evaluatedAt,
            Instant afterCreatedAt,
            UUID afterId,
            int limit) {
        if (filter == null || filter.empty()) {
            return findPage(tenant, afterCreatedAt, afterId, limit);
        }
        if (filter.identityId() != null
                && filter.lifecycleState() == null
                && filter.targetKind() == null
                && filter.targetId() == null
                && filter.provenanceKind() == null
                && filter.validity() == null) {
            return findPageByIdentity(tenant, filter.identityId(), afterCreatedAt, afterId, limit);
        }
        return List.of();
    }

    default List<AccessAssignment> findReviewPage(
            TenantContext tenant, UUID identityId, Instant snapshotAt,
            Instant afterCreatedAt, UUID afterId, int limit) { return List.of(); }

    default List<AccessAssignment> findIdentityReductionPage(
            TenantContext tenant, UUID identityId, Instant snapshotAt,
            Instant afterCreatedAt, UUID afterId, int limit) { return List.of(); }

    List<AccessAssignment> findTargetReductionPage(
            TenantContext tenant,
            AccessAssignment.TargetKind targetKind,
            UUID targetId,
            Instant afterCreatedAt,
            UUID afterId,
            int limit);

    AccessAssignment updateLifecycle(
            TenantContext tenant,
            UUID assignmentId,
            AccessAssignment.LifecycleState lifecycleState,
            long expectedRevision,
            Instant now);

    AccessAssignment terminate(
            TenantContext tenant,
            UUID assignmentId,
            AccessAssignment.LifecycleState terminalState,
            long expectedRevision,
            Instant now);
}
