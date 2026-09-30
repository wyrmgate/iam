package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.identity.domain.AttributeDefinition;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeCardinality;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeType;
import io.wyrmgate.iam.identity.domain.IdentityType;
import io.wyrmgate.iam.identity.domain.SourceCorrelationPolicyVersion;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Commands for immutable activated source-correlation policy versions. */
public final class SourceCorrelationPolicyService {

    private final SourceCorrelationRepository sources;
    private final CanonicalAttributeRepository attributes;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;

    public SourceCorrelationPolicyService(
            SourceCorrelationRepository sources,
            CanonicalAttributeRepository attributes,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this.sources = Objects.requireNonNull(sources, "sources");
        this.attributes = Objects.requireNonNull(attributes, "attributes");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public SourceCorrelationPolicyVersion activate(
            TenantContext tenant,
            UUID sourceSystemId,
            String canonicalKey,
            boolean createIdentityOnNoMatch,
            IdentityType createdIdentityType,
            String displayNameSourcePath,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(sourceSystemId, "sourceSystemId");
        Objects.requireNonNull(now, "now");
        if (canonicalKey == null || canonicalKey.isBlank()) {
            throw new IllegalArgumentException("canonicalKey must not be blank");
        }
        if (createIdentityOnNoMatch) {
            Objects.requireNonNull(createdIdentityType, "createdIdentityType");
            requireSourcePath(displayNameSourcePath, "displayNameSourcePath");
        } else if (createdIdentityType != null || displayNameSourcePath != null) {
            throw new IllegalArgumentException(
                    "creation metadata must be absent when createIdentityOnNoMatch is false");
        }

        return transactions.required(() -> {
            if (sources.findSourceSystem(tenant, sourceSystemId).isEmpty()) {
                throw new IllegalArgumentException("source system does not exist");
            }
            var schema = attributes.findActiveSchemaVersion(tenant)
                    .orElseThrow(() -> new IllegalStateException("no active canonical schema"));
            AttributeDefinition definition = attributes.findAttributeDefinitionByKey(tenant, canonicalKey)
                    .orElseThrow(() -> new IllegalArgumentException("attribute definition does not exist"));
            var version = attributes.findAttributeDefinitionVersion(
                            tenant, schema.id(), definition.id())
                    .orElseThrow(() -> new IllegalStateException(
                            "attribute is not present in active canonical schema"));
            if (version.dataType() != CanonicalAttributeType.STRING
                    || version.cardinality() != CanonicalAttributeCardinality.SINGLE) {
                throw new IllegalArgumentException(
                        "first source correlation policy requires a STRING SINGLE canonical attribute");
            }
            var mapping = attributes.findActiveMapping(tenant, sourceSystemId, version.id())
                    .orElseThrow(() -> new IllegalStateException(
                            "source correlation key requires an active source mapping"));
            requireSourcePath(mapping.sourcePath(), "correlation mapping sourcePath");
            boolean authoritative = attributes.findActiveAuthorityRules(tenant, version.id())
                    .stream()
                    .anyMatch(rule -> rule.sourceSystemId().equals(sourceSystemId));
            if (!authoritative) {
                throw new IllegalStateException(
                        "source correlation key requires active source authority");
            }

            return sources.replaceActiveCorrelationPolicy(
                    tenant,
                    sourceSystemId,
                    version.id(),
                    mapping.id(),
                    createIdentityOnNoMatch,
                    createdIdentityType,
                    displayNameSourcePath,
                    now,
                    ids.nextId());
        });
    }

    private static void requireSourcePath(String value, String name) {
        if (value == null || !value.startsWith("$.") || value.length() < 3) {
            throw new IllegalArgumentException(name + " must use the bounded $.field mapping form");
        }
    }
}
