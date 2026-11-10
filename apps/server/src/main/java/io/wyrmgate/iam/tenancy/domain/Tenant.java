package io.wyrmgate.iam.tenancy.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Tenancy-owned deployment-scoped Tenant isolation realm. */
public record Tenant(
        UUID id,
        String displayName,
        TenantLifecycleState lifecycleState,
        long revision,
        Instant createdAt,
        Instant updatedAt) {

    public Tenant {
        Objects.requireNonNull(id, "id");
        displayName = requireText(displayName, "displayName", 512);
        Objects.requireNonNull(lifecycleState, "lifecycleState");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }

    public static Tenant provisioning(UUID id, String displayName, Instant now) {
        Objects.requireNonNull(now, "now");
        return new Tenant(
                id,
                displayName,
                TenantLifecycleState.PROVISIONING,
                1,
                now,
                now);
    }

    public Tenant transition(TenantLifecycleAction action, Instant now) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(now, "now");
        TenantLifecycleState next = TenantLifecycleDefinition.transition(lifecycleState, action);
        return new Tenant(
                id,
                displayName,
                next,
                revision + 1,
                createdAt,
                now);
    }

    private static String requireText(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + " exceeds maximum length");
        }
        return normalized;
    }
}
