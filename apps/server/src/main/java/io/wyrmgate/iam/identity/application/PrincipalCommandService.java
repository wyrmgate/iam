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

    private final PrincipalRepository principals;
    private final IdentityRepository identities;
    private final CatalogTargetReferenceQuery targets;
    private final PrincipalFactSink facts;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;

    public PrincipalCommandService(
            PrincipalRepository principals,
            IdentityRepository identities,
            CatalogTargetReferenceQuery targets,
            PrincipalFactSink facts,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this.principals = Objects.requireNonNull(principals, "principals");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.facts = Objects.requireNonNull(facts, "facts");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
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
            facts.principalCreated(
                    tenant, persisted, correlationId, causationId);
            if (persisted.identityId() != null) {
                facts.principalCorrelated(
                        tenant, persisted, correlationId, causationId);
            }
            return persisted;
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
