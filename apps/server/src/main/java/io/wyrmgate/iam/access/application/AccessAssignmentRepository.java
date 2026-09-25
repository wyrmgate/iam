package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccessAssignmentRepository {

    void insert(TenantContext tenant, AccessAssignment assignment);

    Optional<AccessAssignment> findById(TenantContext tenant, UUID assignmentId);

    default List<AccessAssignment> findByRoleId(
            TenantContext tenant, UUID roleId) {
        return List.of();
    }

    AccessAssignment terminate(
            TenantContext tenant,
            UUID assignmentId,
            AccessAssignment.LifecycleState terminalState,
            long expectedRevision,
            Instant now);
}
