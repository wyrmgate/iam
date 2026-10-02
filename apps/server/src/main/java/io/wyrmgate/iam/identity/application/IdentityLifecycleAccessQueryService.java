package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.identity.domain.CanonicalAttributeCardinality;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeState;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeType;
import io.wyrmgate.iam.identity.domain.CanonicalValue;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Identity-owned semantic adapter exposing only current lifecycle/access-policy inputs. */
public final class IdentityLifecycleAccessQueryService implements IdentityLifecycleAccessQuery {

    private final IdentityRepository identities;
    private final CanonicalAttributeRepository canonical;
    private final CanonicalAttributeReadRepository reads;

    public IdentityLifecycleAccessQueryService(
            IdentityRepository identities,
            CanonicalAttributeRepository canonical,
            CanonicalAttributeReadRepository reads) {
        this.identities = Objects.requireNonNull(identities, "identities");
        this.canonical = Objects.requireNonNull(canonical, "canonical");
        this.reads = Objects.requireNonNull(reads, "reads");
    }

    @Override
    public boolean supportsPolicyScalarAttribute(
            TenantContext tenant, String canonicalKey, ScalarType type) {
        Objects.requireNonNull(tenant, "tenant");
        if (canonicalKey == null || canonicalKey.isBlank()) {
            return false;
        }
        var schema = canonical.findActiveSchemaVersion(tenant).orElse(null);
        var definition = canonical.findAttributeDefinitionByKey(tenant, canonicalKey).orElse(null);
        if (schema == null || definition == null) {
            return false;
        }
        var version = canonical.findAttributeDefinitionVersion(
                tenant, schema.id(), definition.id()).orElse(null);
        return version != null
                && version.dataType().name().equals(type.name())
                && version.cardinality() == CanonicalAttributeCardinality.SINGLE
                && version.policyAddressable();
    }

    @Override
    public boolean supportsPolicyMultiAttribute(
            TenantContext tenant, String canonicalKey, ScalarType type) {
        Objects.requireNonNull(tenant, "tenant");
        if (canonicalKey == null || canonicalKey.isBlank()) {
            return false;
        }
        var schema = canonical.findActiveSchemaVersion(tenant).orElse(null);
        var definition = canonical.findAttributeDefinitionByKey(tenant, canonicalKey).orElse(null);
        if (schema == null || definition == null) {
            return false;
        }
        var version = canonical.findAttributeDefinitionVersion(
                tenant, schema.id(), definition.id()).orElse(null);
        return version != null
                && version.dataType().name().equals(type.name())
                && version.cardinality() == CanonicalAttributeCardinality.MULTI
                && version.policyAddressable();
    }

    @Override
    public Context currentContext(
            TenantContext tenant,
            UUID identityId,
            Set<String> canonicalKeys) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(identityId, "identityId");
        canonicalKeys = Set.copyOf(Objects.requireNonNull(canonicalKeys, "canonicalKeys"));

        var identity = identities.findById(tenant, identityId).orElse(null);
        if (identity == null) {
            return Context.notFound();
        }

        Map<String, CanonicalScalar> values = new LinkedHashMap<>();
        Map<String, CanonicalMulti> multiValues = new LinkedHashMap<>();
        var schema = canonical.findActiveSchemaVersion(tenant).orElse(null);
        for (String key : canonicalKeys) {
            CanonicalScalar value = CanonicalScalar.unavailable();
            CanonicalMulti multiValue = CanonicalMulti.unavailable();
            if (schema != null) {
                var definition = canonical.findAttributeDefinitionByKey(tenant, key).orElse(null);
                if (definition != null) {
                    var version = canonical.findAttributeDefinitionVersion(
                            tenant, schema.id(), definition.id()).orElse(null);
                    if (version != null
                            && supported(version.dataType())
                            && version.policyAddressable()) {
                        CanonicalAttributeState state = reads.findState(
                                tenant, identityId, definition.id()).orElse(null);
                        if (state != null
                                && (state.resolutionStatus() == CanonicalAttributeState.ResolutionStatus.RESOLVED
                                    || state.resolutionStatus() == CanonicalAttributeState.ResolutionStatus.OVERRIDDEN)) {
                            if (version.cardinality() == CanonicalAttributeCardinality.SINGLE
                                    && state.values().size() == 1) {
                                value = scalar(state.values().getFirst(), state.valueRevision());
                            } else if (version.cardinality() == CanonicalAttributeCardinality.MULTI
                                    && !state.values().isEmpty()
                                    && state.values().stream().allMatch(v -> v.type() == version.dataType())) {
                                multiValue = multi(
                                        version.dataType(), state.values(), state.valueRevision());
                            }
                        }
                    }
                }
            }
            values.put(key, value);
            multiValues.put(key, multiValue);
        }

        return new Context(
                Status.AVAILABLE,
                identity.lifecycleState().name(),
                identity.revision(),
                values,
                multiValues);
    }

    private static boolean supported(CanonicalAttributeType type) {
        return type == CanonicalAttributeType.STRING
                || type == CanonicalAttributeType.BOOLEAN
                || type == CanonicalAttributeType.INTEGER
                || type == CanonicalAttributeType.DECIMAL
                || type == CanonicalAttributeType.DATE
                || type == CanonicalAttributeType.DATETIME
                || type == CanonicalAttributeType.ENUM;
    }

    private static CanonicalMulti multi(
            CanonicalAttributeType type,
            java.util.List<CanonicalValue> values,
            long revision) {
        java.util.List<Object> normalized = values.stream().map(value -> switch (value) {
            case CanonicalValue.StringValue v -> v.value();
            case CanonicalValue.BooleanValue v -> v.value();
            case CanonicalValue.IntegerValue v -> v.value();
            case CanonicalValue.DecimalValue v -> v.value();
            case CanonicalValue.DateValue v -> v.value();
            case CanonicalValue.DateTimeValue v -> v.value();
            case CanonicalValue.EnumValue v -> v.key();
        }).map(value -> (Object) value).toList();
        return CanonicalMulti.trusted(ScalarType.valueOf(type.name()), normalized, revision);
    }

    private static CanonicalScalar scalar(CanonicalValue value, long revision) {
        return switch (value) {
            case CanonicalValue.StringValue v -> CanonicalScalar.trusted(ScalarType.STRING, v.value(), revision);
            case CanonicalValue.BooleanValue v -> CanonicalScalar.trusted(ScalarType.BOOLEAN, v.value(), revision);
            case CanonicalValue.IntegerValue v -> CanonicalScalar.trusted(ScalarType.INTEGER, v.value(), revision);
            case CanonicalValue.DecimalValue v -> CanonicalScalar.trusted(ScalarType.DECIMAL, v.value(), revision);
            case CanonicalValue.DateValue v -> CanonicalScalar.trusted(ScalarType.DATE, v.value(), revision);
            case CanonicalValue.DateTimeValue v -> CanonicalScalar.trusted(ScalarType.DATETIME, v.value(), revision);
            case CanonicalValue.EnumValue v -> CanonicalScalar.trusted(ScalarType.ENUM, v.key(), revision);
            default -> CanonicalScalar.unavailable();
        };
    }
}
