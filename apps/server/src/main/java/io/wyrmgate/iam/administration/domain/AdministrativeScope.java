package io.wyrmgate.iam.administration.domain;

import java.util.Objects;
import java.util.UUID;

/** Strongly typed resource scope for Administrative Authorization. */
public record AdministrativeScope(
        AdministrativeScopeType type,
        String resourceType,
        UUID resourceId,
        String scopeKey) {

    public AdministrativeScope {
        Objects.requireNonNull(type, "type");
        switch (type) {
            case GLOBAL -> {
                if (resourceType != null || resourceId != null || scopeKey != null) {
                    throw new IllegalArgumentException("GLOBAL scope must not carry a resource reference");
                }
            }
            case SPECIFIC_RESOURCE -> {
                if (resourceType == null || resourceType.isBlank() || resourceId == null || scopeKey != null) {
                    throw new IllegalArgumentException(
                            "SPECIFIC_RESOURCE scope requires resourceType/resourceId only");
                }
                resourceType = resourceType.trim();
            }
            case CANONICAL_ATTRIBUTE_CLASSIFICATION -> {
                if (resourceType != null || resourceId != null || scopeKey == null || scopeKey.isBlank()) {
                    throw new IllegalArgumentException(
                            "CANONICAL_ATTRIBUTE_CLASSIFICATION scope requires scopeKey only");
                }
                scopeKey = scopeKey.trim();
            }
            default -> {
                if (resourceType != null || resourceId == null || scopeKey != null) {
                    throw new IllegalArgumentException(
                            type + " scope requires resourceId and no resourceType/scopeKey");
                }
            }
        }
    }

    public AdministrativeScope(AdministrativeScopeType type, String resourceType, UUID resourceId) {
        this(type, resourceType, resourceId, null);
    }

    public static AdministrativeScope global() {
        return new AdministrativeScope(AdministrativeScopeType.GLOBAL, null, null, null);
    }

    public static AdministrativeScope specificResource(String resourceType, UUID resourceId) {
        return new AdministrativeScope(
                AdministrativeScopeType.SPECIFIC_RESOURCE, resourceType, resourceId, null);
    }

    public static AdministrativeScope canonicalAttributeClassification(String classificationKey) {
        return new AdministrativeScope(
                AdministrativeScopeType.CANONICAL_ATTRIBUTE_CLASSIFICATION,
                null,
                null,
                classificationKey);
    }
}
