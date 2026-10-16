package io.wyrmgate.iam.catalog.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Catalog-owned platform application definition; tenant-local state is linked through bindings. */
public record PlatformApplicationOffering(
        UUID id,
        String code,
        String name,
        ApplicationTenancyMode tenancyMode,
        PlatformApplicationAdmissionMode admissionMode,
        String governedPolicyRef,
        CatalogLifecycleState lifecycleState,
        long revision,
        Instant createdAt,
        Instant updatedAt) {

    public PlatformApplicationOffering {
        Objects.requireNonNull(id, "id");
        code = requireText(code, "code", 128);
        name = requireText(name, "name", 512);
        Objects.requireNonNull(tenancyMode, "tenancyMode");
        Objects.requireNonNull(admissionMode, "admissionMode");
        Objects.requireNonNull(lifecycleState, "lifecycleState");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (tenancyMode == ApplicationTenancyMode.TENANT_LOCAL) {
            throw new IllegalArgumentException("platform offering cannot use TENANT_LOCAL tenancy");
        }
        if (admissionMode == PlatformApplicationAdmissionMode.GOVERNED_POLICY) {
            governedPolicyRef = requireText(governedPolicyRef, "governedPolicyRef", 256);
        } else if (governedPolicyRef != null) {
            throw new IllegalArgumentException("governedPolicyRef requires GOVERNED_POLICY admission");
        }
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }

    private static String requireText(String value, String name, int max) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > max) {
            throw new IllegalArgumentException(name + " exceeds maximum length");
        }
        return normalized;
    }
}
