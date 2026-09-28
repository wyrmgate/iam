package io.wyrmgate.iam.access.application;

/** Persistence-neutral signal that one causal provenance already owns an assignment. */
public final class AccessAssignmentProvenanceConflictException
        extends RuntimeException {

    public AccessAssignmentProvenanceConflictException() {
        super("AccessAssignment provenance already exists");
    }
}
