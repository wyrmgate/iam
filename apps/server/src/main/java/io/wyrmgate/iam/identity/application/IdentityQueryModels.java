package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.identity.domain.AttributeDefinition;
import io.wyrmgate.iam.identity.domain.AttributeDefinitionVersion;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeCardinality;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeState;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeType;
import io.wyrmgate.iam.identity.domain.Identity;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Bounded application query models for authoritative Identity API reads. */
public final class IdentityQueryModels {

    private IdentityQueryModels() {
    }

    public record IdentityPagePosition(Instant createdAt, UUID id) {
        public IdentityPagePosition {
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(id, "id");
        }
    }

    public record CanonicalAttributePagePosition(String key, UUID definitionId) {
        public CanonicalAttributePagePosition {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("key must not be blank");
            }
            Objects.requireNonNull(definitionId, "definitionId");
        }
    }

    public record CanonicalAttributeDefinitionEntry(
            AttributeDefinition definition,
            AttributeDefinitionVersion version) {
        public CanonicalAttributeDefinitionEntry {
            Objects.requireNonNull(definition, "definition");
            Objects.requireNonNull(version, "version");
            if (!definition.id().equals(version.attributeDefinitionId())) {
                throw new IllegalArgumentException("definition version must belong to definition");
            }
        }
    }

    public record IdentityPage(List<Identity> items, IdentityPagePosition nextPosition) {
        public IdentityPage {
            items = List.copyOf(Objects.requireNonNull(items, "items"));
        }
    }

    public record CanonicalAttributeReadView(
            UUID definitionId,
            UUID definitionVersionId,
            String key,
            String classification,
            CanonicalAttributeType type,
            CanonicalAttributeCardinality cardinality,
            CanonicalAttributeState.ResolutionStatus resolutionStatus,
            long valueRevision,
            boolean hasTrustedValue) {
        public CanonicalAttributeReadView {
            Objects.requireNonNull(definitionId, "definitionId");
            Objects.requireNonNull(definitionVersionId, "definitionVersionId");
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("key must not be blank");
            }
            if (classification == null || classification.isBlank()) {
                throw new IllegalArgumentException("classification must not be blank");
            }
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(cardinality, "cardinality");
            Objects.requireNonNull(resolutionStatus, "resolutionStatus");
            if (valueRevision < 1) {
                throw new IllegalArgumentException("valueRevision must be positive");
            }
        }
    }

    public record CanonicalAttributePage(
            List<CanonicalAttributeReadView> items,
            CanonicalAttributePagePosition nextPosition) {
        public CanonicalAttributePage {
            items = List.copyOf(Objects.requireNonNull(items, "items"));
        }
    }
}
