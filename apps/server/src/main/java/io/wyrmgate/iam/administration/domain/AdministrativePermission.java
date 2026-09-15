package io.wyrmgate.iam.administration.domain;

/** Stable semantic permission for operating the IAM control plane. */
public record AdministrativePermission(String resourceType, String action) {

    public AdministrativePermission {
        resourceType = requireToken(resourceType, "resourceType");
        action = requireToken(action, "action");
    }

    public String key() {
        return resourceType + ":" + action;
    }

    private static String requireToken(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        String normalized = value.trim();
        if (!normalized.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException(name + " must be a lower-case semantic token");
        }
        return normalized;
    }
}
