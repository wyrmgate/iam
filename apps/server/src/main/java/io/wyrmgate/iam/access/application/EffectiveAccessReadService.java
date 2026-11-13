package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.application.AccessQueryModels.EffectiveDetail;
import io.wyrmgate.iam.access.application.AccessQueryModels.EffectivePage;
import io.wyrmgate.iam.access.application.AccessQueryModels.EffectivePosition;
import io.wyrmgate.iam.access.application.AccessQueryModels.EffectiveReadSummary;
import io.wyrmgate.iam.access.application.AccessQueryModels.EffectiveSupport;
import io.wyrmgate.iam.access.application.AccessQueryModels.EffectiveSupportPage;
import io.wyrmgate.iam.access.application.AccessQueryModels.EffectiveSupportPosition;
import io.wyrmgate.iam.access.domain.EffectiveAccess;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQuery;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class EffectiveAccessReadService {

    private static final int DEFAULT_SUPPORT_PAGE = 100;

    private final EffectiveAccessRepository repository;
    private final IdentityAccessReferenceQuery identities;
    private final EffectiveAccessSemanticSupport semanticSupport;
    private final EffectiveAccessSupportQuery supportQuery;

    public EffectiveAccessReadService(
            EffectiveAccessRepository repository,
            IdentityAccessReferenceQuery identities,
            EffectiveAccessSemanticSupport semanticSupport,
            EffectiveAccessSupportQuery supportQuery) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.semanticSupport = Objects.requireNonNull(
                semanticSupport, "semanticSupport");
        this.supportQuery = Objects.requireNonNull(
                supportQuery, "supportQuery");
    }

    /** Compatibility constructor retained for focused tests with small support sets. */
    public EffectiveAccessReadService(
            EffectiveAccessRepository repository,
            IdentityAccessReferenceQuery identities,
            EffectiveAccessSemanticSupport semanticSupport) {
        this(
                repository,
                identities,
                semanticSupport,
                new RepositorySupportQuery(repository));
    }

    public EffectivePage list(
            TenantContext tenant,
            UUID identityId,
            EffectivePosition after,
            int limit,
            Instant at) {
        requireLimit(limit);
        Objects.requireNonNull(at, "at");
        if (identityId != null && !accessEligible(tenant, identityId)) {
            return new EffectivePage(List.of(), null);
        }

        List<EffectiveAccess> fetched = repository.findCurrentPage(
                tenant,
                identityId,
                after == null ? null : after.id(),
                limit + 1,
                at);
        boolean more = fetched.size() > limit;
        List<EffectiveAccess> selected = fetched.subList(
                0, Math.min(limit, fetched.size()));
        List<EffectiveReadSummary> semantic = new ArrayList<>();

        for (EffectiveAccess value : selected) {
            if (!accessEligible(tenant, value.identityId())) {
                continue;
            }
            SupportSlice supports = semanticPage(
                    tenant,
                    value,
                    null,
                    DEFAULT_SUPPORT_PAGE,
                    at);
            if (supports.items().isEmpty()) {
                if (supports.nextPosition() != null) {
                    throw new EffectiveAccessSemanticEvaluationException();
                }
                continue;
            }
            semantic.add(new EffectiveReadSummary(
                    withSupportCount(value, supports.items().size()),
                    supports.nextPosition() != null));
        }

        EffectivePosition next = more && !selected.isEmpty()
                ? new EffectivePosition(selected.getLast().id())
                : null;
        return new EffectivePage(List.copyOf(semantic), next);
    }

    public Optional<EffectiveDetail> find(
            TenantContext tenant,
            UUID effectiveAccessId,
            Instant at) {
        Objects.requireNonNull(at, "at");
        return repository.findCurrentById(
                        tenant, effectiveAccessId, at)
                .filter(value -> accessEligible(
                        tenant, value.identityId()))
                .flatMap(value -> {
                    SupportSlice supports = semanticPage(
                            tenant,
                            value,
                            null,
                            DEFAULT_SUPPORT_PAGE,
                            at);
                    if (supports.items().isEmpty()) {
                        if (supports.nextPosition() != null) {
                            throw new EffectiveAccessSemanticEvaluationException();
                        }
                        return Optional.empty();
                    }
                    return Optional.of(new EffectiveDetail(
                            withSupportCount(
                                    value, supports.items().size()),
                            supports.items(),
                            supports.nextPosition() != null,
                            supports.nextPosition()));
                });
    }

    public Optional<EffectiveSupportPage> supports(
            TenantContext tenant,
            UUID effectiveAccessId,
            EffectiveSupportPosition after,
            int limit,
            Instant at) {
        requireSupportLimit(limit);
        Objects.requireNonNull(at, "at");
        return repository.findCurrentById(
                        tenant, effectiveAccessId, at)
                .filter(value -> accessEligible(
                        tenant, value.identityId()))
                .map(value -> {
                    SupportSlice slice = semanticPage(
                            tenant,
                            value,
                            after,
                            limit,
                            at);
                    return new EffectiveSupportPage(
                            slice.items(),
                            slice.nextPosition());
                });
    }

    private SupportSlice semanticPage(
            TenantContext tenant,
            EffectiveAccess value,
            EffectiveSupportPosition after,
            int limit,
            Instant at) {
        List<EffectiveSupport> raw = supportQuery.page(
                tenant,
                value.id(),
                after,
                limit + 1,
                at);
        boolean more = raw.size() > limit;
        List<EffectiveSupport> selected = raw.subList(
                0, Math.min(limit, raw.size()));
        List<EffectiveSupport> semantic = selected.stream()
                .filter(support -> semanticSupport.contributes(
                        tenant,
                        support.accessAssignmentId(),
                        value.entitlementId(),
                        at))
                .toList();
        EffectiveSupportPosition next = more && !selected.isEmpty()
                ? position(selected.getLast())
                : null;
        return new SupportSlice(
                List.copyOf(semantic),
                next);
    }

    private boolean accessEligible(
            TenantContext tenant,
            UUID identityId) {
        return identities.accessStatus(
                        tenant, identityId)
                .status()
                == IdentityAccessReferenceQuery.AccessStatus.ACCESS_ELIGIBLE;
    }

    private static EffectiveAccess withSupportCount(
            EffectiveAccess value,
            int supportCount) {
        return new EffectiveAccess(
                value.id(),
                value.identityId(),
                value.entitlementId(),
                value.principalConstraintKey(),
                supportCount,
                value.computedAt(),
                value.projectionGeneration());
    }

    private static EffectiveSupportPosition position(
            EffectiveSupport support) {
        return new EffectiveSupportPosition(
                support.accessAssignmentId(),
                support.pathHash());
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and 200");
        }
    }

    private static void requireSupportLimit(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException(
                    "support limit must be between 1 and 100");
        }
    }

    private record SupportSlice(
            List<EffectiveSupport> items,
            EffectiveSupportPosition nextPosition) {}

    private static final class RepositorySupportQuery
            implements EffectiveAccessSupportQuery {
        private final EffectiveAccessRepository repository;

        private RepositorySupportQuery(
                EffectiveAccessRepository repository) {
            this.repository = repository;
        }

        @Override
        public List<EffectiveSupport> page(
                TenantContext tenant,
                UUID effectiveAccessId,
                EffectiveSupportPosition after,
                int limit,
                Instant at) {
            List<EffectiveSupport> values =
                    repository.currentSupportEvidence(
                            tenant, effectiveAccessId, at);
            return values.stream()
                    .filter(value -> after == null
                            || compare(position(value), after) > 0)
                    .limit(limit)
                    .toList();
        }

        @Override
        public int countCurrent(
                TenantContext tenant,
                UUID effectiveAccessId,
                Instant at) {
            return repository.currentSupportEvidence(
                    tenant, effectiveAccessId, at).size();
        }

        private static int compare(
                EffectiveSupportPosition left,
                EffectiveSupportPosition right) {
            int byAssignment = left.accessAssignmentId()
                    .compareTo(right.accessAssignmentId());
            return byAssignment != 0
                    ? byAssignment
                    : left.pathHash().compareTo(right.pathHash());
        }
    }
}
