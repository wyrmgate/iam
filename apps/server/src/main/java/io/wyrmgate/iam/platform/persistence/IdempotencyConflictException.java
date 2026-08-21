package io.wyrmgate.iam.platform.persistence;

/** Same causal key was reused for a materially different request. */
public final class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String operationNamespace, String idempotencyKey) {
        super("Idempotency key conflict in operation namespace "
                + operationNamespace + " for key " + idempotencyKey);
    }
}
