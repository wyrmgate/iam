package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.identity.domain.Identity;
import io.wyrmgate.iam.identity.domain.IdentityLifecycleState;
import io.wyrmgate.iam.identity.domain.IdentityMergeOperation;
import io.wyrmgate.iam.identity.domain.IdentityProfile;
import io.wyrmgate.iam.identity.domain.IdentitySplitOperation;
import io.wyrmgate.iam.identity.domain.Principal;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Explicit Identity-owned merge/split semantics preserving historical references. */
public final class IdentityMergeSplitService {

    private static final int MAX_RELATIONSHIP_MOVES = 200;

    private final IdentityMergeSplitRepository operations;
    private final IdentityRepository identities;
    private final SourceCorrelationRepository sources;
    private final SourceCorrelationFactSink sourceFacts;
    private final PrincipalFactSink principalFacts;
    private final IdentityCommandService identityCommands;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;

    public IdentityMergeSplitService(
            IdentityMergeSplitRepository operations,
            IdentityRepository identities,
            SourceCorrelationRepository sources,
            SourceCorrelationFactSink sourceFacts,
            PrincipalFactSink principalFacts,
            IdentityCommandService identityCommands,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this.operations = Objects.requireNonNull(operations, "operations");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.sources = Objects.requireNonNull(sources, "sources");
        this.sourceFacts = Objects.requireNonNull(sourceFacts, "sourceFacts");
        this.principalFacts = Objects.requireNonNull(principalFacts, "principalFacts");
        this.identityCommands = Objects.requireNonNull(identityCommands, "identityCommands");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public IdentityMergeOperation merge(
            TenantContext tenant,
            UUID survivorIdentityId,
            long expectedSurvivorRevision,
            UUID absorbedIdentityId,
            long expectedAbsorbedRevision,
            String reason,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(survivorIdentityId, "survivorIdentityId");
        Objects.requireNonNull(absorbedIdentityId, "absorbedIdentityId");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(correlationId, "correlationId");
        String normalizedReason = requireReason(reason);
        if (survivorIdentityId.equals(absorbedIdentityId)) {
            throw new IllegalArgumentException("merge requires two distinct Identities");
        }
        requirePositive(expectedSurvivorRevision, "expectedSurvivorRevision");
        requirePositive(expectedAbsorbedRevision, "expectedAbsorbedRevision");

        UUID operationId = ids.nextId();
        return transactions.required(() -> {
            var locked = operations.lockIdentities(
                    tenant, List.of(survivorIdentityId, absorbedIdentityId));
            Identity survivor = identity(locked, survivorIdentityId);
            Identity absorbed = identity(locked, absorbedIdentityId);
            requireRevision(survivor, expectedSurvivorRevision);
            requireRevision(absorbed, expectedAbsorbedRevision);
            requireMergeable(survivor, absorbed);
            if (operations.hasCompletedMergeForAbsorbedIdentity(
                    tenant, absorbedIdentityId)) {
                throw new IllegalStateException("absorbed Identity already has completed merge history");
            }

            var links = operations.findActiveLinksByIdentity(
                    tenant, absorbedIdentityId);
            var principals = operations.findPrincipalsByIdentity(
                    tenant, absorbedIdentityId);
            requireBounded(links.size(), "merge source links");
            requireBounded(principals.size(), "merge Principals");

            int movedLinks = 0;
            for (var link : links) {
                var replacement = sources.replaceAcceptedLink(
                        tenant,
                        link.sourceRecordId(),
                        survivorIdentityId,
                        "identity merge " + operationId,
                        now,
                        correlationId,
                        operationId,
                        ids.nextId());
                if (replacement.changed()) {
                    movedLinks++;
                    sourceFacts.identityLinkAccepted(
                            tenant,
                            replacement.link(),
                            replacement.previousIdentityId());
                }
            }

            int movedPrincipals = 0;
            for (Principal principal : principals) {
                Principal moved = operations.reassignPrincipal(
                        tenant,
                        principal.id(),
                        absorbedIdentityId,
                        survivorIdentityId,
                        principal.revision(),
                        now);
                movedPrincipals++;
                principalFacts.principalReassigned(
                        tenant,
                        moved,
                        absorbedIdentityId,
                        correlationId,
                        operationId);
            }

            identityCommands.changeLifecycle(
                    tenant,
                    absorbedIdentityId,
                    IdentityLifecycleState.DECOMMISSIONED,
                    absorbed.revision(),
                    now,
                    correlationId,
                    operationId);

            IdentityMergeOperation operation = new IdentityMergeOperation(
                    operationId,
                    survivorIdentityId,
                    absorbedIdentityId,
                    survivor.revision(),
                    absorbed.revision(),
                    movedLinks,
                    movedPrincipals,
                    normalizedReason,
                    correlationId,
                    causationId,
                    now);
            operations.insertMergeOperation(tenant, operation);
            return operation;
        });
    }

    public SplitResult split(
            TenantContext tenant,
            UUID sourceIdentityId,
            long expectedSourceRevision,
            String newDisplayName,
            List<UUID> sourceRecordIds,
            List<UUID> principalIds,
            String reason,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(sourceIdentityId, "sourceIdentityId");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(correlationId, "correlationId");
        requirePositive(expectedSourceRevision, "expectedSourceRevision");
        String normalizedReason = requireReason(reason);
        if (newDisplayName == null || newDisplayName.isBlank()) {
            throw new IllegalArgumentException("newDisplayName must not be blank");
        }
        List<UUID> selectedSources =
                uniqueSelection(sourceRecordIds, "sourceRecordIds");
        List<UUID> selectedPrincipals =
                uniqueSelection(principalIds, "principalIds");
        if (selectedSources.isEmpty() && selectedPrincipals.isEmpty()) {
            throw new IllegalArgumentException(
                    "split must move at least one SourceRecord link or Principal");
        }

        UUID operationId = ids.nextId();
        return transactions.required(() -> {
            Identity source = operations.lockIdentities(
                            tenant, List.of(sourceIdentityId))
                    .getFirst();
            requireRevision(source, expectedSourceRevision);
            if (source.lifecycleState() == IdentityLifecycleState.DECOMMISSIONED) {
                throw new IllegalStateException("DECOMMISSIONED Identity cannot be split");
            }

            var currentLinks = operations.findActiveLinksByIdentity(
                    tenant, sourceIdentityId);
            var currentPrincipals = operations.findPrincipalsByIdentity(
                    tenant, sourceIdentityId);
            var linksByRecord = currentLinks.stream().collect(
                    java.util.stream.Collectors.toMap(
                            link -> link.sourceRecordId(),
                            java.util.function.Function.identity()));
            var principalsById = currentPrincipals.stream().collect(
                    java.util.stream.Collectors.toMap(
                            Principal::id,
                            java.util.function.Function.identity()));

            for (UUID sourceRecordId : selectedSources) {
                if (!linksByRecord.containsKey(sourceRecordId)) {
                    throw new IllegalArgumentException(
                            "selected SourceRecord is not currently linked to the source Identity");
                }
            }
            for (UUID principalId : selectedPrincipals) {
                if (!principalsById.containsKey(principalId)) {
                    throw new IllegalArgumentException(
                            "selected Principal is not currently owned by the source Identity");
                }
            }

            Identity created = identityCommands.create(
                    tenant,
                    source.type(),
                    profile(source),
                    IdentityLifecycleState.PENDING,
                    newDisplayName,
                    now,
                    correlationId,
                    operationId);

            for (UUID sourceRecordId : selectedSources) {
                var replacement = sources.replaceAcceptedLink(
                        tenant,
                        sourceRecordId,
                        created.id(),
                        "identity split " + operationId,
                        now,
                        correlationId,
                        operationId,
                        ids.nextId());
                if (!replacement.changed()) {
                    throw new IllegalStateException(
                            "split SourceRecord link did not change");
                }
                sourceFacts.identityLinkAccepted(
                        tenant,
                        replacement.link(),
                        replacement.previousIdentityId());
            }

            for (UUID principalId : selectedPrincipals) {
                Principal principal = principalsById.get(principalId);
                Principal moved = operations.reassignPrincipal(
                        tenant,
                        principal.id(),
                        sourceIdentityId,
                        created.id(),
                        principal.revision(),
                        now);
                principalFacts.principalReassigned(
                        tenant,
                        moved,
                        sourceIdentityId,
                        correlationId,
                        operationId);
            }

            IdentitySplitOperation operation = new IdentitySplitOperation(
                    operationId,
                    sourceIdentityId,
                    created.id(),
                    source.revision(),
                    selectedSources,
                    selectedPrincipals,
                    normalizedReason,
                    correlationId,
                    causationId,
                    now);
            operations.insertSplitOperation(tenant, operation);
            return new SplitResult(operation, created);
        });
    }

    private static Identity identity(
            List<Identity> identities,
            UUID id) {
        return identities.stream()
                .filter(identity -> identity.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Identity does not exist"));
    }

    private static void requireMergeable(
            Identity survivor,
            Identity absorbed) {
        if (survivor.type() != absorbed.type()) {
            throw new IllegalArgumentException("merge requires matching IdentityType");
        }
        if (survivor.lifecycleState() == IdentityLifecycleState.DECOMMISSIONED
                || absorbed.lifecycleState() == IdentityLifecycleState.DECOMMISSIONED) {
            throw new IllegalStateException("DECOMMISSIONED Identity cannot participate in merge");
        }
    }

    private static void requireRevision(
            Identity identity,
            long expectedRevision) {
        if (identity.revision() != expectedRevision) {
            throw new StaleWriteException(
                    "identity", identity.id(), expectedRevision);
        }
    }

    private static void requirePositive(
            long value,
            String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireBounded(
            int count,
            String name) {
        if (count > MAX_RELATIONSHIP_MOVES) {
            throw new IllegalStateException(
                    name + " exceed first-slice bound of " + MAX_RELATIONSHIP_MOVES);
        }
    }

    private static List<UUID> uniqueSelection(
            List<UUID> values,
            String name) {
        List<UUID> result = values == null ? List.of() : List.copyOf(values);
        if (result.size() > MAX_RELATIONSHIP_MOVES) {
            throw new IllegalArgumentException(
                    name + " exceed first-slice bound of " + MAX_RELATIONSHIP_MOVES);
        }
        if (result.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(name + " must not contain null");
        }
        Set<UUID> unique = new HashSet<>(result);
        if (unique.size() != result.size()) {
            throw new IllegalArgumentException(name + " must not contain duplicates");
        }
        return result;
    }

    private static IdentityProfile profile(
            Identity identity) {
        return switch (identity.type()) {
            case PERSON -> new IdentityProfile.PersonProfile();
            case SERVICE -> new IdentityProfile.ServiceProfile();
            case WORKLOAD -> new IdentityProfile.WorkloadProfile();
        };
    }

    private static String requireReason(
            String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        String normalized = reason.trim();
        if (normalized.length() > 1024) {
            throw new IllegalArgumentException("reason exceeds 1024 characters");
        }
        return normalized;
    }

    public record SplitResult(
            IdentitySplitOperation operation,
            Identity newIdentity) {
        public SplitResult {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(newIdentity, "newIdentity");
        }
    }
}
