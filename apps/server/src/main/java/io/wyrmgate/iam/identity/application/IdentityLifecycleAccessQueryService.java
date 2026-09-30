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
    public boolean supportsPolicySingleStringAttribute(
            TenantContext tenant, String canonicalKey) {
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
                && version.dataType() == CanonicalAttributeType.STRING
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

        Map<String, CanonicalString> values = new LinkedHashMap<>();
        var schema = canonical.findActiveSchemaVersion(tenant).orElse(null);
        for (String key : canonicalKeys) {
            CanonicalString value = CanonicalString.unavailable();
            if (schema != null) {
                var definition = canonical.findAttributeDefinitionByKey(tenant, key).orElse(null);
                if (definition != null) {
                    var version = canonical.findAttributeDefinitionVersion(
                            tenant, schema.id(), definition.id()).orElse(null);
                    if (version != null
                            && version.dataType() == CanonicalAttributeType.STRING
                            && version.cardinality() == CanonicalAttributeCardinality.SINGLE
                            && version.policyAddressable()) {
                        CanonicalAttributeState state = reads.findState(
                                tenant, identityId, definition.id()).orElse(null);
                        if (state != null
                                && (state.resolutionStatus() == CanonicalAttributeState.ResolutionStatus.RESOLVED
                                    || state.resolutionStatus() == CanonicalAttributeState.ResolutionStatus.OVERRIDDEN)
                                && state.values().size() == 1
                                && state.values().getFirst() instanceof CanonicalValue.StringValue stringValue) {
                            value = CanonicalString.trusted(
                                    stringValue.value(), state.valueRevision());
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
}
