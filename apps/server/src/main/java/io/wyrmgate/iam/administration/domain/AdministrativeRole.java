package io.wyrmgate.iam.administration.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Manageable bundle of semantic IAM control-plane permissions. */
public record AdministrativeRole(
        UUID id,
        String code,
        String name,
        Set<AdministrativePermission> permissions,
        long revision,
        Instant createdAt,
        Instant updatedAt) {

    public AdministrativeRole {
        Objects.requireNonNull(id, "id");
        code = requireText(code, "code");
        name = requireText(name, "name");
        permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions"));
        if (permissions.isEmpty()) {
            throw new IllegalArgumentException("permissions must not be empty");
        }
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
