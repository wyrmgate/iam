package io.wyrmgate.iam.platform.persistence;

import java.util.UUID;

/** Narrow JDBC helper for revision-guarded authoritative updates. */
public final class OptimisticUpdate {

    private OptimisticUpdate() {
    }

    public static void requireSingleRow(
            int affectedRows,
            String resourceType,
            UUID resourceId,
            long expectedRevision) {
        if (affectedRows == 0) {
            throw new StaleWriteException(resourceType, resourceId, expectedRevision);
        }
        if (affectedRows != 1) {
            throw new IllegalStateException(
                    "Expected one " + resourceType + " row to be updated, but affected " + affectedRows);
        }
    }
}
