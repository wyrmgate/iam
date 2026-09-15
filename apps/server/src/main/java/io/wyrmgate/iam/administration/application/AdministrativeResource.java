package io.wyrmgate.iam.administration.application;

import java.util.Objects;
import java.util.UUID;

/** Semantic IAM resource presented to Administrative Authorization. */
public record AdministrativeResource(String resourceType, UUID resourceId) {

    public AdministrativeResource {
        if (resourceType == null || resourceType.isBlank()) {
            throw new IllegalArgumentException("resourceType must not be blank");
        }
        resourceType = resourceType.trim();
        Objects.requireNonNull(resourceId, "resourceId");
    }
}
