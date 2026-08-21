package io.wyrmgate.iam.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Stable canonical attribute identity; semantic shape is versioned separately. */
public record AttributeDefinition(
        UUID id,
        String canonicalKey,
        LifecycleState lifecycleState,
        long revision,
        Instant createdAt,
        Instant updatedAt) {

    public enum LifecycleState { ACTIVE, RETIRED }

    public AttributeDefinition {
        Objects.requireNonNull(id, "id");
        if (canonicalKey == null || canonicalKey.isBlank()) {
            throw new IllegalArgumentException("canonicalKey must not be blank");
        }
        Objects.requireNonNull(lifecycleState, "lifecycleState");
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt must not precede createdAt");
    }
}
