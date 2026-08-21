package io.wyrmgate.iam.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable semantic shape of one stable AttributeDefinition inside a schema version. */
public record AttributeDefinitionVersion(
        UUID id,
        UUID schemaVersionId,
        UUID attributeDefinitionId,
        CanonicalAttributeType dataType,
        CanonicalAttributeCardinality cardinality,
        String classification,
        boolean queryable,
        boolean searchable,
        boolean policyAddressable,
        Instant createdAt) {

    public AttributeDefinitionVersion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(schemaVersionId, "schemaVersionId");
        Objects.requireNonNull(attributeDefinitionId, "attributeDefinitionId");
        Objects.requireNonNull(dataType, "dataType");
        Objects.requireNonNull(cardinality, "cardinality");
        if (classification == null || classification.isBlank()) {
            throw new IllegalArgumentException("classification must not be blank");
        }
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public void validateValues(java.util.List<CanonicalValue> values) {
        Objects.requireNonNull(values, "values");
        if (values.isEmpty()) throw new IllegalArgumentException("canonical values must not be empty");
        if (cardinality == CanonicalAttributeCardinality.SINGLE && values.size() != 1) {
            throw new IllegalArgumentException("SINGLE attribute requires exactly one value");
        }
        if (values.stream().anyMatch(value -> value == null || value.type() != dataType)) {
            throw new IllegalArgumentException("canonical value type is incompatible with definition");
        }
    }
}
