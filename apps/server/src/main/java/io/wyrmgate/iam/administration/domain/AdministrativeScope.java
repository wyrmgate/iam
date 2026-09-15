package io.wyrmgate.iam.administration.domain;

import java.util.Objects;
import java.util.UUID;

/** Strongly typed resource scope for Administrative Authorization. */
public record AdministrativeScope(AdministrativeScopeType type, String resourceType, UUID resourceId) {

    public AdministrativeScope {
        Objects.requireNonNull(type, "type");
        switch (type) {
            case GLOBAL -> {
                if (resourceType != null || resourceId != null) {
                    throw new IllegalArgumentException("GLOBAL scope must not carry a resource reference");
                }
            }
            case SPECIFIC_RESOURCE -> {
                if (resourceType == null || resourceType.isBlank() || resourceId == null) {
                    throw new IllegalArgumentException(
                            "SPECIFIC_RESOURCE scope requires resourceType and resourceId");
                }
                resourceType = resourceType.trim();
            }
            default -> {
                if (resourceType != null || resourceId == null) {
                    throw new IllegalArgumentException(
                            type + " scope requires resourceId and no resourceType discriminator");
                }
            }
        }
    }

    public static AdministrativeScope global() {
        return new AdministrativeScope(AdministrativeScopeType.GLOBAL, null, null);
    }

    public static AdministrativeScope specificResource(String resourceType, UUID resourceId) {
        return new AdministrativeScope(
                AdministrativeScopeType.SPECIFIC_RESOURCE, resourceType, resourceId);
    }
}
