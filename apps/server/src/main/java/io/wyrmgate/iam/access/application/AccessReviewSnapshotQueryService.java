package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class AccessReviewSnapshotQueryService
        implements AccessReviewSnapshotQuery {

    private final AccessAssignmentRepository assignments;

    public AccessReviewSnapshotQueryService(
            AccessAssignmentRepository assignments) {
        this.assignments = Objects.requireNonNull(
                assignments, "assignments");
    }

    @Override
    public Page page(
            TenantContext tenant,
            UUID identityId,
            Instant snapshotAt,
            Position after,
            int limit) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(snapshotAt, "snapshotAt");
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and 500");
        }

        List<AccessAssignment> rows =
                assignments.findReviewPage(
                        tenant,
                        identityId,
                        snapshotAt,
                        after == null
                                ? null
                                : after.createdAt(),
                        after == null
                                ? null
                                : after.id(),
                        limit + 1);
        boolean hasMore = rows.size() > limit;
        List<AccessAssignment> selected =
                hasMore
                        ? List.copyOf(
                                rows.subList(0, limit))
                        : List.copyOf(rows);
        Position next = hasMore
                ? new Position(
                        selected.getLast().createdAt(),
                        selected.getLast().id())
                : null;
        return new Page(
                selected.stream()
                        .map(AccessReviewSnapshotQueryService
                                ::item)
                        .toList(),
                next);
    }

    private static Item item(
            AccessAssignment assignment) {
        return new Item(
                assignment.id(),
                assignment.revision(),
                assignment.targetKind().name(),
                assignment.roleId(),
                assignment.entitlementId(),
                assignment.principalConstraintKind().name(),
                assignment.specificPrincipalId(),
                assignment.provenanceKind().name(),
                assignment.provenanceRefId(),
                assignment.lifecycleState().name(),
                assignment.validFrom(),
                assignment.validUntil(),
                assignment.createdAt());
    }
}
