package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Access-owned semantic query used by Governance review generation. */
public interface AccessReviewSnapshotQuery {

    Page page(
            TenantContext tenant,
            UUID identityId,
            Instant snapshotAt,
            Position after,
            int limit);

    record Position(Instant createdAt, UUID id) {}

    record Page(
            List<Item> items,
            Position nextPosition) {
        public Page {
            items = List.copyOf(items);
        }
    }

    record Item(
            UUID accessAssignmentId,
            long assignmentRevision,
            String targetKind,
            UUID roleId,
            UUID entitlementId,
            String principalConstraintKind,
            UUID specificPrincipalId,
            String provenanceKind,
            UUID provenanceRefId,
            String lifecycleState,
            Instant validFrom,
            Instant validUntil,
            Instant createdAt) {}
}
