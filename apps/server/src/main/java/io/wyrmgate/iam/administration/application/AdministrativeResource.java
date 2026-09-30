package io.wyrmgate.iam.administration.application;

import java.util.UUID;

/** Semantic IAM resource presented to Administrative Authorization. */
public record AdministrativeResource(String resourceType, UUID resourceId, String scopeKey) {

    public AdministrativeResource(String resourceType, UUID resourceId) {
        this(resourceType, resourceId, null);
    }

    public AdministrativeResource {
        if (resourceType == null || resourceType.isBlank()) {
            throw new IllegalArgumentException("resourceType must not be blank");
        }
        resourceType = resourceType.trim();
        if (scopeKey != null) {
            if (scopeKey.isBlank()) {
                throw new IllegalArgumentException("scopeKey must not be blank");
            }
            scopeKey = scopeKey.trim();
        }
    }

    public static AdministrativeResource collection(String resourceType) {
        return new AdministrativeResource(resourceType, null, null);
    }

    public static AdministrativeResource classification(String resourceType, String classification) {
        return new AdministrativeResource(resourceType, null, classification);
    }
}
