package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.catalog.application.CatalogTargetReferenceQuery;
import io.wyrmgate.iam.identity.domain.Principal;
import io.wyrmgate.iam.identity.domain.PrincipalKind;
import io.wyrmgate.iam.identity.domain.PrincipalLifecycleState;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Identity-owned commands for the first authoritative Principal slice. */
public final class PrincipalCommandService {

    private static final PrincipalAdministrativeContinuityGuard ALLOW_ALL_CONTINUITY =
            (tenant, principalId, identityId, at) -> {};

    private final PrincipalRepository principals;
    private final IdentityRepository identities;
    private final CatalogTargetReferenceQuery targets;
    private final PrincipalFactSink facts;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;
    private final PrincipalAdministrativeContinuityGuard administrativeContinuityGuard;

    /** Compatibility constructor for isolated tests/compositions without Administration. */
    public PrincipalCommandService(
            PrincipalRepository principals,
            IdentityRepository identities,
            CatalogTargetReferenceQuery targets,
            PrincipalFactSink facts,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this(
                principals,
                identities,
                targets,
                facts,
                ids,
                transactions,
                ALLOW_ALL_CONTINUITY);
    }

    public PrincipalCommandService(
            PrincipalRepository principals,
            IdentityRepository identities,
            CatalogTargetReferenceQuery targets,
            PrincipalFactSink facts,
            IdGenerator ids,
            TransactionExecutor transactions,
            PrincipalAdministrativeContinuityGuard administrativeContinuityGuard) {
        this.principals = Objects.requireNonNull(principals, "principals");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.facts = Objects.requireNonNull(facts, "facts");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.administrativeContinuityGuard = Objects.requireNonNull(
                administrativeContinuityGuard, "administrativeContinuityGuard");
    }

    public Principal create(
            TenantContext tenant,
            UUID applicationTargetId,
            String nativePrincipalKey,
            UUID identityId,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(applicationTargetId, "applicationTargetId");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(correlationId, "correlationId");

        requireActiveTarget(tenant, applicationTargetId);
        if (identityId != null && identities.findById(tenant, identityId).isEmpty()) {
            throw new PrincipalCommandException(
                    "identity_not_found",
                    "The requested Identity was not found in the tenant.");
        }

        Principal principal = new Principal(
                ids.nextId(),
                identityId,
                applicationTargetId,
                PrincipalKind.ACCOUNT,
                nativePrincipalKey,
                PrincipalLifecycleState.ACTIVE,
                1,
                now,
                now);

        return transactions.required(() -> {
            principals.insert(tenant, principal);
            Principal persisted = principals.findById(tenant, principal.id())
                    .orElseThrow(() -> new IllegalStateException(
                            "created principal could not be reloaded"));
            if (persisted.identityId() != null) {
                facts.principalCorrelated(
                        tenant, persisted, correlationId, causationId);
            }
            return persisted;
        });
    }

    /** Idempotent owner-local command used by fresh-install orchestration. */
    public Principal ensureInstallationPrincipal(
            TenantContext tenant,
            UUID principalId,
            UUID applicationTargetId,
            String nativePrincipalKey,
            UUID identityId,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(applicationTargetId, "applicationTargetId");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(correlationId, "correlationId");

        requireActiveTarget(tenant, applicationTargetId);
        if (identityId != null && identities.findById(tenant, identityId).isEmpty()) {
            throw new PrincipalCommandException(
                    "identity_not_found",
                    "The requested Identity was not found in the tenant.");
        }

        Principal desired = new Principal(
                principalId,
                identityId,
                applicationTargetId,
                PrincipalKind.ACCOUNT,
                nativePrincipalKey,
                PrincipalLifecycleState.ACTIVE,
                1,
                now,
                now);

        return transactions.required(() -> {
            Principal existingById = principals.findById(tenant, principalId).orElse(null);
            if (existingById != null) {
                if (sameInstallationPrincipal(existingById, desired)) {
                    return existingById;
                }
                throw new PrincipalCommandException(
                        "principal_id_conflict",
                        "The preallocated Principal ID already exists with different semantics.");
            }
            Principal existingByKey = principals.findByTargetAndNativeKey(
                    tenant, applicationTargetId, nativePrincipalKey).orElse(null);
            if (existingByKey != null) {
                if (existingByKey.id().equals(principalId)
                        && sameInstallationPrincipal(existingByKey, desired)) {
                    return existingByKey;
                }
                throw new PrincipalCommandException(
                        "principal_native_key_conflict",
                        "The native Principal key is already used by another Principal on the target.");
            }
            principals.insert(tenant, desired);
            Principal persisted = principals.findById(tenant, desired.id())
                    .orElseThrow(() -> new IllegalStateException(
                            "created principal could not be reloaded"));
            if (persisted.identityId() != null) {
                facts.principalCorrelated(
                        tenant, persisted, correlationId, causationId);
            }
            return persisted;
        });
    }

    private static boolean sameInstallationPrincipal(Principal existing, Principal desired) {
        return Objects.equals(existing.identityId(), desired.identityId())
                && existing.applicationTargetId().equals(desired.applicationTargetId())
                && existing.kind() == PrincipalKind.ACCOUNT
                && existing.nativePrincipalKey().equals(desired.nativePrincipalKey())
                && existing.lifecycleState() == PrincipalLifecycleState.ACTIVE;
    }

    public Principal applyProvisioningState(
            TenantContext tenant,
            UUID identityId,
            UUID applicationTargetId,
            String nativePrincipalKey,
            PrincipalLifecycleState desiredState,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(applicationTargetId, "applicationTargetId");
        Objects.requireNonNull(desiredState, "desiredState");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(correlationId, "correlationId");
        requireActiveTarget(tenant, applicationTargetId);
        if (identities.findById(tenant, identityId).isEmpty()) {
            throw new PrincipalCommandException(
                    "identity_not_found",
                    "The requested Identity was not found in the tenant.");
        }

        return transactions.required(() -> {
            Principal existing = principals.findByTargetAndNativeKey(
                    tenant, applicationTargetId, nativePrincipalKey).orElse(null);
            if (existing == null) {
                if (desiredState != PrincipalLifecycleState.ACTIVE) {
                    throw new PrincipalCommandException(
                            "principal_not_found",
                            "A disable result cannot create a new Principal.");
                }
                Principal created = new Principal(
                        ids.nextId(),
                        identityId,
                        applicationTargetId,
                        PrincipalKind.ACCOUNT,
                        nativePrincipalKey,
                        PrincipalLifecycleState.ACTIVE,
                        1,
                        now,
                        now);
                principals.insert(tenant, created);
                Principal persisted = principals.findById(tenant, created.id())
                        .orElseThrow();
                facts.principalCorrelated(
                        tenant, persisted, correlationId, causationId);
                return persisted;
            }
            if (!Objects.equals(existing.identityId(), identityId)) {
                throw new PrincipalCommandException(
                        "principal_identity_conflict",
                        "The provider Principal is not correlated to the expected Identity.");
            }
            if (existing.lifecycleState() == desiredState) {
                return existing;
            }
            if (existing.lifecycleState() == PrincipalLifecycleState.ACTIVE
                    && desiredState == PrincipalLifecycleState.DISABLED) {
                administrativeContinuityGuard.assertMayDisable(
                        tenant, existing.id(), identityId, now);
            }
            Principal updated = principals.updateLifecycle(
                    tenant,
                    existing.id(),
                    desiredState,
                    existing.revision(),
                    now);
            facts.principalAccessProjectionChanged(
                    tenant, updated, correlationId, causationId);
            return updated;
        });
    }

    public Principal correlate(
            TenantContext tenant,
            UUID principalId,
            UUID identityId,
            long expectedRevision,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(correlationId, "correlationId");
        if (expectedRevision < 1) {
            throw new IllegalArgumentException("expectedRevision must be positive");
        }
        if (identities.findById(tenant, identityId).isEmpty()) {
            throw new PrincipalCommandException(
                    "identity_not_found",
                    "The requested Identity was not found in the tenant.");
        }

        return transactions.required(() -> {
            Principal updated = principals.correlate(
                    tenant, principalId, identityId, expectedRevision, now);
            facts.principalCorrelated(
                    tenant, updated, correlationId, causationId);
            return updated;
        });
    }

    private void requireActiveTarget(
            TenantContext tenant, UUID applicationTargetId) {
        switch (targets.validateActiveTarget(tenant, applicationTargetId)) {
            case VALID -> { }
            case NOT_FOUND -> throw new PrincipalCommandException(
                    "application_target_not_found",
                    "The requested ApplicationTarget was not found.");
            case RETIRED -> throw new PrincipalCommandException(
                    "application_target_retired",
                    "The requested ApplicationTarget is retired.");
        }
    }
}
