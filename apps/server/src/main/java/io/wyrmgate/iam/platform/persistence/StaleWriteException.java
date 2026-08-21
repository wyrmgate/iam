package io.wyrmgate.iam.platform.persistence;

import java.util.UUID;

/** Raised when an optimistic update no longer matches the expected revision. */
public final class StaleWriteException extends RuntimeException {

    public StaleWriteException(String resourceType, UUID resourceId, long expectedRevision) {
        super("Stale " + resourceType + " write for " + resourceId + " at expected revision " + expectedRevision);
    }
}
