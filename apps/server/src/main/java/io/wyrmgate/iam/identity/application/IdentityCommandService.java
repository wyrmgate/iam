package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.identity.domain.Identity;
import io.wyrmgate.iam.identity.domain.IdentityLifecycleState;
import io.wyrmgate.iam.identity.domain.IdentityProfile;
import io.wyrmgate.iam.identity.domain.IdentityType;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Application commands for the first authoritative Identity persistence slice. */
public final class IdentityCommandService {

    private static final IdentityAdministrativeContinuityGuard ALLOW_ALL_CONTINUITY =
            (tenant, identityId, at) -> {};

    private final IdentityRepository repository;
    private final IdentityFactSink factSink;
    private final IdGenerator idGenerator;
    private final TransactionExecutor transactions;
    private final IdentityAdministrativeContinuityGuard administrativeContinuityGuard;

    /** Compatibility constructor for isolated tests/compositions without Administration. */
    public IdentityCommandService(
            IdentityRepository repository,
            IdentityFactSink factSink,
            IdGenerator idGenerator,
            TransactionExecutor transactions) {
        this(repository, factSink, idGenerator, transactions, ALLOW_ALL_CONTINUITY);
    }

    public IdentityCommandService(
            IdentityRepository repository,
            IdentityFactSink factSink,
            IdGenerator idGenerator,
            TransactionExecutor transactions,
            IdentityAdministrativeContinuityGuard administrativeContinuityGuard) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.factSink = Objects.requireNonNull(factSink, "factSink");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.administrativeContinuityGuard = Objects.requireNonNull(
                administrativeContinuityGuard, "administrativeContinuityGuard");
    }

    public Identity create(
            TenantContext tenant,
            IdentityType type,
            IdentityProfile profile,
            IdentityLifecycleState lifecycleState,
            String displayName,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        return createWithId(
                tenant,
                idGenerator.nextId(),
                type,
                profile,
                lifecycleState,
                displayName,
                now,
                correlationId,
                causationId,
                false);
    }

    /**
     * Idempotent owner-local command used only by the trusted fresh-install orchestrator.
     * A retry succeeds only when the exact preallocated Identity already exists with the
     * expected bootstrap semantics.
     */
    public Identity ensureInstallationPerson(
            TenantContext tenant,
            UUID identityId,
            String displayName,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        return createWithId(
                tenant,
                identityId,
                IdentityType.PERSON,
                new IdentityProfile.PersonProfile(),
                IdentityLifecycleState.ACTIVE,
                displayName,
                now,
                correlationId,
                causationId,
                true);
    }

    private Identity createWithId(
            TenantContext tenant,
            UUID identityId,
            IdentityType type,
            IdentityProfile profile,
            IdentityLifecycleState lifecycleState,
            String displayName,
            Instant now,
            UUID correlationId,
            UUID causationId,
            boolean idempotent) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(lifecycleState, "lifecycleState");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(correlationId, "correlationId");

        Identity desired = new Identity(
                identityId,
                type,
                profile,
                lifecycleState,
                displayName,
                1,
                now,
                now);

        return transactions.required(() -> {
            Identity existing = repository.findById(tenant, identityId).orElse(null);
            if (existing != null) {
                if (idempotent && sameInstallationIdentity(existing, desired)) {
                    return existing;
                }
                throw new IllegalStateException("Identity ID already exists with different semantics");
            }
            repository.insert(tenant, desired);
            Identity persisted = repository.findById(tenant, desired.id())
                    .orElseThrow(() -> new IllegalStateException("created identity could not be reloaded"));
            factSink.identityCreated(tenant, persisted, correlationId, causationId);
            return persisted;
        });
    }

    private static boolean sameInstallationIdentity(Identity existing, Identity desired) {
        return existing.type() == IdentityType.PERSON
                && existing.profile() instanceof IdentityProfile.PersonProfile
                && existing.lifecycleState() == IdentityLifecycleState.ACTIVE
                && existing.displayName().equals(desired.displayName());
    }

    public Identity changeLifecycle(
            TenantContext tenant,
            UUID identityId,
            IdentityLifecycleState lifecycleState,
            long expectedRevision,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(lifecycleState, "lifecycleState");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(correlationId, "correlationId");
        if (expectedRevision < 1) {
            throw new IllegalArgumentException(
                    "expectedRevision must be positive");
        }

        return transactions.required(() -> {
            Identity current = repository.findById(
                            tenant, identityId)
                    .orElseThrow(() ->
                            new IllegalArgumentException(
                                    "Identity does not exist"));
            if (current.revision() != expectedRevision) {
                throw new io.wyrmgate.iam.platform.persistence
                        .StaleWriteException(
                                "identity",
                                identityId,
                                expectedRevision);
            }
            if (current.lifecycleState() == lifecycleState) {
                return current;
            }
            requireLifecycleTransition(
                    current.lifecycleState(),
                    lifecycleState);
            if (current.lifecycleState() == IdentityLifecycleState.ACTIVE
                    && lifecycleState != IdentityLifecycleState.ACTIVE) {
                administrativeContinuityGuard.assertMayBecomeAdministrativelyIneligible(
                        tenant, identityId, now);
            }
            Identity updated = repository.updateLifecycle(
                    tenant,
                    identityId,
                    lifecycleState,
                    expectedRevision,
                    now);
            factSink.lifecycleChanged(
                    tenant,
                    current.lifecycleState(),
                    updated,
                    correlationId,
                    causationId);
            return updated;
        });
    }

    private static void requireLifecycleTransition(
            IdentityLifecycleState from,
            IdentityLifecycleState to) {
        if (from == IdentityLifecycleState.DECOMMISSIONED) {
            throw new IllegalStateException(
                    "DECOMMISSIONED Identity is terminal");
        }
        boolean allowed = switch (from) {
            case PENDING ->
                    to == IdentityLifecycleState.ACTIVE
                            || to == IdentityLifecycleState.INACTIVE
                            || to == IdentityLifecycleState.DECOMMISSIONED;
            case ACTIVE ->
                    to == IdentityLifecycleState.SUSPENDED
                            || to == IdentityLifecycleState.INACTIVE
                            || to == IdentityLifecycleState.DECOMMISSIONED;
            case SUSPENDED ->
                    to == IdentityLifecycleState.ACTIVE
                            || to == IdentityLifecycleState.INACTIVE
                            || to == IdentityLifecycleState.DECOMMISSIONED;
            case INACTIVE ->
                    to == IdentityLifecycleState.ACTIVE
                            || to == IdentityLifecycleState.DECOMMISSIONED;
            case DECOMMISSIONED -> false;
        };
        if (!allowed) {
            throw new IllegalArgumentException(
                    "invalid Identity lifecycle transition "
                            + from + " -> " + to);
        }
    }

    public Identity changeDisplayName(
            TenantContext tenant,
            UUID identityId,
            String displayName,
            long expectedRevision,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(correlationId, "correlationId");
        if (expectedRevision < 1) {
            throw new IllegalArgumentException("expectedRevision must be positive");
        }

        return transactions.required(() -> {
            Identity updated = repository.updateDisplayName(
                    tenant, identityId, displayName, expectedRevision, now);
            factSink.displayNameChanged(tenant, updated, correlationId, causationId);
            return updated;
        });
    }
}
