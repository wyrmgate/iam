package io.wyrmgate.iam.administration.application;

import java.util.UUID;

/** Semantic IAM resource presented to Administrative Authorization. */
public record AdministrativeResource(String resourceType, UUID resourceId) {

    public AdministrativeResource {
        if (resourceType == null || resourceType.isBlank()) {
            throw new IllegalArgumentException("resourceType must not be blank");
        }
        resourceType = resourceType.trim();
    }

    public static AdministrativeResource collection(String resourceType) {
        return new AdministrativeResource(resourceType, null);
    }
}
