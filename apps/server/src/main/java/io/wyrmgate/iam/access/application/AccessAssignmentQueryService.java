package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.application.AccessQueryModels.AssignmentFilter;
import io.wyrmgate.iam.access.application.AccessQueryModels.AssignmentPage;
import io.wyrmgate.iam.access.application.AccessQueryModels.AssignmentPosition;
import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class AccessAssignmentQueryService {

    private final AccessAssignmentRepository assignments;

    public AccessAssignmentQueryService(AccessAssignmentRepository assignments) {
        this.assignments = Objects.requireNonNull(assignments, "assignments");
    }

    public Optional<AccessAssignment> find(TenantContext tenant, UUID assignmentId) {
        return assignments.findById(tenant, assignmentId);
    }

    public AssignmentPage list(TenantContext tenant, AssignmentPosition after, int limit) {
        return list(tenant, AssignmentFilter.none(), after, limit, Instant.now());
    }

    public AssignmentPage list(
            TenantContext tenant,
            UUID identityId,
            AssignmentPosition after,
            int limit) {
        return list(
                tenant,
                new AssignmentFilter(identityId, null, null, null, null, null),
                after,
                limit,
                Instant.now());
    }

    public AssignmentPage list(
            TenantContext tenant,
            AssignmentFilter filter,
            AssignmentPosition after,
            int limit,
            Instant evaluatedAt) {
        requireLimit(limit);
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        List<AccessAssignment> fetched = assignments.findPageFiltered(
                tenant,
                filter,
                evaluatedAt,
                after == null ? null : after.createdAt(),
                after == null ? null : after.id(),
                limit + 1);
        boolean more = fetched.size() > limit;
        List<AccessAssignment> items = List.copyOf(
                fetched.subList(0, Math.min(limit, fetched.size())));
        AssignmentPosition next = more
                ? new AssignmentPosition(items.getLast().createdAt(), items.getLast().id())
                : null;
        return new AssignmentPage(items, next);
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
    }
}
