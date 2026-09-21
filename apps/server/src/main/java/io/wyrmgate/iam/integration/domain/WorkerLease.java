package io.wyrmgate.iam.integration.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record WorkerLease(UUID leaseId, long leaseEpoch, Instant leaseExpiresAt) {
    public WorkerLease {
        Objects.requireNonNull(leaseId, "leaseId");
        Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        if (leaseEpoch < 1) throw new IllegalArgumentException("leaseEpoch must be positive");
    }
}
