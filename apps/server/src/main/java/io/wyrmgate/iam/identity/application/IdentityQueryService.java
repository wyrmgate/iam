package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.identity.application.CanonicalAttributeResolutionEvaluator.EffectiveResolution;
import io.wyrmgate.iam.identity.application.IdentityQueryModels.CanonicalAttributeDefinitionEntry;
import io.wyrmgate.iam.identity.application.IdentityQueryModels.CanonicalAttributePage;
import io.wyrmgate.iam.identity.application.IdentityQueryModels.CanonicalAttributePagePosition;
import io.wyrmgate.iam.identity.application.IdentityQueryModels.CanonicalAttributeReadView;
import io.wyrmgate.iam.identity.application.IdentityQueryModels.IdentityPage;
import io.wyrmgate.iam.identity.application.IdentityQueryModels.IdentityPagePosition;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeOverride;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeState;
import io.wyrmgate.iam.identity.domain.Identity;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Authoritative bounded Identity reads, including live temporal canonical-resolution semantics. */
public final class IdentityQueryService {

    private final IdentityRepository identities;
    private final IdentityQueryRepository queries;
    private final CanonicalAttributeRepository canonicalAttributes;
    private final CanonicalAttributeReadRepository canonicalReads;
    private final CanonicalAttributeResolutionEvaluator evaluator;

    public IdentityQueryService(
            IdentityRepository identities,
            IdentityQueryRepository queries,
            CanonicalAttributeRepository canonicalAttributes,
            CanonicalAttributeReadRepository canonicalReads,
            CanonicalAttributeResolutionEvaluator evaluator) {
        this.identities = Objects.requireNonNull(identities, "identities");
        this.queries = Objects.requireNonNull(queries, "queries");
        this.canonicalAttributes = Objects.requireNonNull(canonicalAttributes, "canonicalAttributes");
        this.canonicalReads = Objects.requireNonNull(canonicalReads, "canonicalReads");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
    }

    public Optional<Identity> findIdentity(TenantContext tenant, UUID identityId) {
        return identities.findById(tenant, identityId);
    }

    public IdentityPage listIdentities(TenantContext tenant, IdentityPagePosition after, int limit) {
        requireLimit(limit);
        List<Identity> fetched = queries.findIdentityPage(tenant, after, limit + 1);
        boolean hasMore = fetched.size() > limit;
        List<Identity> items = List.copyOf(fetched.subList(0, Math.min(limit, fetched.size())));
        IdentityPagePosition next = hasMore
                ? new IdentityPagePosition(items.getLast().createdAt(), items.getLast().id())
                : null;
        return new IdentityPage(items, next);
    }

    public CanonicalAttributePage listCanonicalAttributes(
            TenantContext tenant,
            UUID identityId,
            CanonicalAttributePagePosition after,
            int limit,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(now, "now");
        requireLimit(limit);
        if (identities.findById(tenant, identityId).isEmpty()) {
            throw new IllegalArgumentException("identity does not exist");
        }

        List<CanonicalAttributeDefinitionEntry> fetched =
                queries.findActiveCanonicalDefinitionPage(tenant, after, limit + 1);
        boolean hasMore = fetched.size() > limit;
        List<CanonicalAttributeDefinitionEntry> entries =
                fetched.subList(0, Math.min(limit, fetched.size()));
        List<CanonicalAttributeReadView> items = new ArrayList<>(entries.size());
        for (CanonicalAttributeDefinitionEntry entry : entries) {
            CanonicalAttributeState current = canonicalReads.findState(
                    tenant, identityId, entry.definition().id()).orElse(null);
            CanonicalAttributeOverride override = canonicalAttributes.findActiveOverride(
                    tenant, identityId, entry.definition().id()).orElse(null);
            EffectiveResolution effective = evaluator.evaluate(
                    current,
                    entry.version(),
                    override,
                    canonicalAttributes.findCandidates(tenant, identityId, entry.version().id()),
                    canonicalAttributes.findActiveAuthorityRules(tenant, entry.version().id()),
                    now);
            items.add(new CanonicalAttributeReadView(
                    entry.definition().id(),
                    entry.version().id(),
                    entry.definition().canonicalKey(),
                    entry.version().classification(),
                    entry.version().dataType(),
                    entry.version().cardinality(),
                    effective.resolutionStatus(),
                    evaluator.effectiveValueRevision(current, effective),
                    !effective.values().isEmpty()));
        }

        CanonicalAttributePagePosition next = hasMore
                ? new CanonicalAttributePagePosition(
                        entries.getLast().definition().canonicalKey(),
                        entries.getLast().definition().id())
                : null;
        return new CanonicalAttributePage(items, next);
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
    }
}
