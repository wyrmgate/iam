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
        var schema = canonical.findActiveSchemaVersion(tenant).orElse(null);
        for (String key : canonicalKeys) {
            CanonicalScalar value = CanonicalScalar.unavailable();
            if (schema != null) {
                var definition = canonical.findAttributeDefinitionByKey(tenant, key).orElse(null);
                if (definition != null) {
                    var version = canonical.findAttributeDefinitionVersion(
                            tenant, schema.id(), definition.id()).orElse(null);
                    if (version != null
                            && supported(version.dataType())
                            && version.cardinality() == CanonicalAttributeCardinality.SINGLE
                            && version.policyAddressable()) {
                        CanonicalAttributeState state = reads.findState(
                                tenant, identityId, definition.id()).orElse(null);
                        if (state != null
                                && (state.resolutionStatus() == CanonicalAttributeState.ResolutionStatus.RESOLVED
                                    || state.resolutionStatus() == CanonicalAttributeState.ResolutionStatus.OVERRIDDEN)
                                && state.values().size() == 1) {
                            value = scalar(state.values().getFirst(), state.valueRevision());
                        }
                    }
                }
            }
            values.put(key, value);
        }

        return new Context(
                Status.AVAILABLE,
                identity.lifecycleState().name(),
                identity.revision(),
                values);
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
