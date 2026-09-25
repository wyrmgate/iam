package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AccessAssignmentRepository {

    void insert(TenantContext tenant, AccessAssignment assignment);

    Optional<AccessAssignment> findById(TenantContext tenant, UUID assignmentId);

    AccessAssignment terminate(
            TenantContext tenant,
            UUID assignmentId,
            AccessAssignment.LifecycleState terminalState,
            long expectedRevision,
            Instant now);
}
