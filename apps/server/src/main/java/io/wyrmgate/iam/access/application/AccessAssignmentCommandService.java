package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.catalog.application.CatalogAccessReferenceQuery;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQuery;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Access-owned commands for the first authoritative AccessAssignment slice. */
public final class AccessAssignmentCommandService {

    private final AccessAssignmentRepository assignments;
    private final IdentityAccessReferenceQuery identityReferences;
    private final CatalogAccessReferenceQuery catalogReferences;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;

    public AccessAssignmentCommandService(
            AccessAssignmentRepository assignments,
            IdentityAccessReferenceQuery identityReferences,
            CatalogAccessReferenceQuery catalogReferences,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this.assignments = Objects.requireNonNull(assignments, "assignments");
        this.identityReferences = Objects.requireNonNull(
                identityReferences, "identityReferences");
        this.catalogReferences = Objects.requireNonNull(
                catalogReferences, "catalogReferences");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public AccessAssignment createEntitlementAssignment(
            TenantContext tenant,
            UUID identityId,
            UUID entitlementId,
            AccessAssignment.PrincipalConstraintKind principalConstraintKind,
            UUID specificPrincipalId,
            Instant validFrom,
            Instant validUntil,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(entitlementId, "entitlementId");
        Objects.requireNonNull(principalConstraintKind, "principalConstraintKind");
        Objects.requireNonNull(now, "now");

        if (!identityReferences.identityExists(tenant, identityId)) {
            throw new AccessAssignmentCommandException(
                    "identity_not_found",
                    "The requested Identity was not found in the tenant.");
        }

        var entitlement = catalogReferences.resolveActiveEntitlement(
                tenant, entitlementId);
        switch (entitlement.status()) {
            case NOT_FOUND -> throw new AccessAssignmentCommandException(
                    "entitlement_not_found",
                    "The requested Entitlement was not found.");
            case RETIRED -> throw new AccessAssignmentCommandException(
                    "entitlement_retired",
                    "The requested Entitlement is retired.");
            case UNTARGETED -> throw new AccessAssignmentCommandException(
                    "entitlement_untargeted",
                    "The first AccessAssignment slice requires a target-scoped Entitlement.");
            case VALID -> { }
        }

        if (principalConstraintKind
                == AccessAssignment.PrincipalConstraintKind.SPECIFIC) {
            if (specificPrincipalId == null) {
                throw new AccessAssignmentCommandException(
                        "specific_principal_required",
                        "SPECIFIC principal constraint requires a Principal.");
            }
            var principal = identityReferences.principal(
                    tenant, specificPrincipalId);
            if (principal.status()
                    == IdentityAccessReferenceQuery.Status.NOT_FOUND) {
                throw new AccessAssignmentCommandException(
                        "principal_not_found",
                        "The requested Principal was not found.");
            }
            if (principal.status()
                    == IdentityAccessReferenceQuery.Status.UNCORRELATED) {
                throw new AccessAssignmentCommandException(
                        "principal_uncorrelated",
                        "The requested Principal is not correlated to an Identity.");
            }
            if (!identityId.equals(principal.identityId())) {
                throw new AccessAssignmentCommandException(
                        "principal_identity_mismatch",
                        "The requested Principal belongs to another Identity.");
            }
            if (!entitlement.applicationTargetId().equals(
                    principal.applicationTargetId())) {
                throw new AccessAssignmentCommandException(
                        "principal_target_mismatch",
                        "The requested Principal belongs to another ApplicationTarget.");
            }
        } else if (specificPrincipalId != null) {
            throw new AccessAssignmentCommandException(
                    "specific_principal_not_allowed",
                    "ANY principal constraint must not include a specific Principal.");
        }

        if (validUntil != null && !validUntil.isAfter(now)) {
            throw new AccessAssignmentCommandException(
                    "validity_already_ended",
                    "AccessAssignment validity must not already be ended.");
        }
        if (validFrom != null && validUntil != null
                && !validUntil.isAfter(validFrom)) {
            throw new AccessAssignmentCommandException(
                    "invalid_validity_window",
                    "validUntil must be after validFrom.");
        }

        AccessAssignment.LifecycleState lifecycleState =
                validFrom != null && validFrom.isAfter(now)
                        ? AccessAssignment.LifecycleState.SCHEDULED
                        : AccessAssignment.LifecycleState.ACTIVE;

        AccessAssignment assignment = new AccessAssignment(
                ids.nextId(),
                identityId,
                AccessAssignment.TargetKind.ENTITLEMENT,
                null,
                entitlementId,
                principalConstraintKind,
                specificPrincipalId,
                AccessAssignment.ProvenanceKind.MANUAL,
                null,
                lifecycleState,
                validFrom,
                validUntil,
                1,
                now,
                now);

        return transactions.required(() -> {
            assignments.insert(tenant, assignment);
            return assignments.findById(tenant, assignment.id())
                    .orElseThrow(() -> new IllegalStateException(
                            "created AccessAssignment could not be reloaded"));
        });
    }

    public AccessAssignment terminate(
            TenantContext tenant,
            UUID assignmentId,
            long expectedRevision,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(assignmentId, "assignmentId");
        Objects.requireNonNull(now, "now");
        if (expectedRevision < 1) {
            throw new IllegalArgumentException(
                    "expectedRevision must be positive");
        }

        return transactions.required(() -> {
            AccessAssignment current = assignments.findById(
                            tenant, assignmentId)
                    .orElseThrow(() -> new AccessAssignmentCommandException(
                            "access_assignment_not_found",
                            "The requested AccessAssignment was not found."));

            if (current.revision() != expectedRevision) {
                throw new io.wyrmgate.iam.platform.persistence.StaleWriteException(
                        "access-assignment", assignmentId, expectedRevision);
            }

            if (current.lifecycleState()
                    == AccessAssignment.LifecycleState.REVOKED
                    || current.lifecycleState()
                    == AccessAssignment.LifecycleState.EXPIRED
                    || current.lifecycleState()
                    == AccessAssignment.LifecycleState.CANCELLED) {
                throw new AccessAssignmentCommandException(
                        "access_assignment_terminal",
                        "The AccessAssignment is already terminal.");
            }

            AccessAssignment.LifecycleState terminalState;
            if (current.validUntil() != null
                    && !current.validUntil().isAfter(now)) {
                terminalState = AccessAssignment.LifecycleState.EXPIRED;
            } else if (current.lifecycleState()
                    == AccessAssignment.LifecycleState.SCHEDULED
                    && current.validFrom() != null
                    && now.isBefore(current.validFrom())) {
                terminalState = AccessAssignment.LifecycleState.CANCELLED;
            } else {
                terminalState = AccessAssignment.LifecycleState.REVOKED;
            }

            return assignments.terminate(
                    tenant,
                    assignmentId,
                    terminalState,
                    expectedRevision,
                    now);
        });
    }
}
