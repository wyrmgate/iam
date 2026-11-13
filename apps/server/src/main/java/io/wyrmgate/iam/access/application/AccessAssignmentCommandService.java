package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.access.domain.AccessAssignmentLifecycleDefinition;
import io.wyrmgate.iam.access.domain.AccessAssignmentLifecycleDefinition.Action;
import io.wyrmgate.iam.catalog.application.CatalogAccessReferenceQuery;
import io.wyrmgate.iam.catalog.application.CatalogAccessTargetAvailabilityQuery;
import io.wyrmgate.iam.catalog.domain.CatalogAccessTargetKind;
import io.wyrmgate.iam.catalog.application.RoleExpansionQuery;
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
    private final CatalogAccessTargetAvailabilityQuery catalogAvailability;
    private final RoleExpansionQuery roleExpansion;
    private final AccessAssignmentFactSink facts;
    private final AccessAssignmentBoundaryScheduler boundaries;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;

    public AccessAssignmentCommandService(
            AccessAssignmentRepository assignments,
            IdentityAccessReferenceQuery identityReferences,
            CatalogAccessReferenceQuery catalogReferences,
            CatalogAccessTargetAvailabilityQuery catalogAvailability,
            RoleExpansionQuery roleExpansion,
            AccessAssignmentFactSink facts,
            AccessAssignmentBoundaryScheduler boundaries,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this.assignments = Objects.requireNonNull(assignments, "assignments");
        this.identityReferences = Objects.requireNonNull(
                identityReferences, "identityReferences");
        this.catalogReferences = Objects.requireNonNull(
                catalogReferences, "catalogReferences");
        this.catalogAvailability = Objects.requireNonNull(
                catalogAvailability, "catalogAvailability");
        this.roleExpansion = Objects.requireNonNull(
                roleExpansion, "roleExpansion");
        this.facts = Objects.requireNonNull(facts, "facts");
        this.boundaries = Objects.requireNonNull(boundaries, "boundaries");
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
        return createEntitlementAssignmentInternal(
                tenant,
                identityId,
                entitlementId,
                principalConstraintKind,
                specificPrincipalId,
                validFrom,
                validUntil,
                AccessAssignment.ProvenanceKind.MANUAL,
                null,
                null,
                null,
                now);
    }

    public AccessAssignment createRequestedEntitlementAssignment(
            TenantContext tenant,
            UUID requestItemId,
            UUID identityId,
            UUID entitlementId,
            AccessAssignment.PrincipalConstraintKind principalConstraintKind,
            UUID specificPrincipalId,
            Instant validFrom,
            Instant validUntil,
            Instant now) {
        Objects.requireNonNull(requestItemId, "requestItemId");
        return createEntitlementAssignmentInternal(
                tenant,
                identityId,
                entitlementId,
                principalConstraintKind,
                specificPrincipalId,
                validFrom,
                validUntil,
                AccessAssignment.ProvenanceKind.REQUEST_ITEM,
                requestItemId,
                null,
                null,
                now);
    }

    public AccessAssignment createLifecyclePolicyEntitlementAssignment(
            TenantContext tenant,
            UUID ruleId,
            UUID identityId,
            UUID entitlementId,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        Objects.requireNonNull(ruleId, "ruleId");
        return createEntitlementAssignmentInternal(
                tenant,
                identityId,
                entitlementId,
                AccessAssignment.PrincipalConstraintKind.ANY,
                null,
                null,
                null,
                AccessAssignment.ProvenanceKind.LIFECYCLE_POLICY_RULE,
                ruleId,
                correlationId,
                causationId,
                now);
    }

    private AccessAssignment createEntitlementAssignmentInternal(
            TenantContext tenant,
            UUID identityId,
            UUID entitlementId,
            AccessAssignment.PrincipalConstraintKind principalConstraintKind,
            UUID specificPrincipalId,
            Instant validFrom,
            Instant validUntil,
            AccessAssignment.ProvenanceKind provenanceKind,
            UUID provenanceRefId,
            UUID correlationId,
            UUID causationId,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(entitlementId, "entitlementId");
        Objects.requireNonNull(principalConstraintKind, "principalConstraintKind");
        Objects.requireNonNull(now, "now");

        var identityStatus = requireAccessEligibleIdentity(
                tenant, identityId);

        var entitlementAvailability = catalogAvailability.currentAvailability(
                        tenant, CatalogAccessTargetKind.ENTITLEMENT, entitlementId)
                .orElseThrow(() -> new AccessAssignmentCommandException(
                        "entitlement_not_found",
                        "The requested Entitlement was not found."));
        if (!entitlementAvailability.assignable()) {
            throw new AccessAssignmentCommandException(
                    entitlementAvailability.terminal()
                            ? "entitlement_unavailable_terminal"
                            : "entitlement_unavailable_temporary",
                    "The requested Entitlement is not currently assignable.");
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
            case TARGET_RETIRED -> throw new AccessAssignmentCommandException(
                    "application_target_retired",
                    "The Entitlement belongs to a retired ApplicationTarget.");
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
                identityStatus.accessEligibilityEpoch(),
                AccessAssignment.TargetKind.ENTITLEMENT,
                null,
                entitlementId,
                principalConstraintKind,
                specificPrincipalId,
                provenanceKind,
                provenanceRefId,
                lifecycleState,
                validFrom,
                validUntil,
                1,
                now,
                now);

        return transactions.required(() -> {
            assignments.insert(tenant, assignment);
            facts.projectionInputChanged(
                    tenant, assignment, correlationId, causationId);
            boundaries.scheduleBoundaries(tenant, assignment, now);
            return assignments.findById(tenant, assignment.id())
                    .orElseThrow(() -> new IllegalStateException(
                            "created AccessAssignment could not be reloaded"));
        });
    }

    public AccessAssignment createRoleAssignment(
            TenantContext tenant,
            UUID identityId,
            UUID roleId,
            AccessAssignment.PrincipalConstraintKind principalConstraintKind,
            UUID specificPrincipalId,
            Instant validFrom,
            Instant validUntil,
            Instant now) {
        return createRoleAssignmentInternal(
                tenant,
                identityId,
                roleId,
                principalConstraintKind,
                specificPrincipalId,
                validFrom,
                validUntil,
                AccessAssignment.ProvenanceKind.MANUAL,
                null,
                null,
                null,
                now);
    }

    public AccessAssignment createRequestedRoleAssignment(
            TenantContext tenant,
            UUID requestItemId,
            UUID identityId,
            UUID roleId,
            AccessAssignment.PrincipalConstraintKind principalConstraintKind,
            UUID specificPrincipalId,
            Instant validFrom,
            Instant validUntil,
            Instant now) {
        Objects.requireNonNull(requestItemId, "requestItemId");
        return createRoleAssignmentInternal(
                tenant,
                identityId,
                roleId,
                principalConstraintKind,
                specificPrincipalId,
                validFrom,
                validUntil,
                AccessAssignment.ProvenanceKind.REQUEST_ITEM,
                requestItemId,
                null,
                null,
                now);
    }

    public AccessAssignment createLifecyclePolicyRoleAssignment(
            TenantContext tenant,
            UUID ruleId,
            UUID identityId,
            UUID roleId,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        Objects.requireNonNull(ruleId, "ruleId");
        return createRoleAssignmentInternal(
                tenant,
                identityId,
                roleId,
                AccessAssignment.PrincipalConstraintKind.ANY,
                null,
                null,
                null,
                AccessAssignment.ProvenanceKind.LIFECYCLE_POLICY_RULE,
                ruleId,
                correlationId,
                causationId,
                now);
    }

    private AccessAssignment createRoleAssignmentInternal(
            TenantContext tenant,
            UUID identityId,
            UUID roleId,
            AccessAssignment.PrincipalConstraintKind principalConstraintKind,
            UUID specificPrincipalId,
            Instant validFrom,
            Instant validUntil,
            AccessAssignment.ProvenanceKind provenanceKind,
            UUID provenanceRefId,
            UUID correlationId,
            UUID causationId,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(roleId, "roleId");
        Objects.requireNonNull(principalConstraintKind, "principalConstraintKind");
        Objects.requireNonNull(now, "now");

        var identityStatus = requireAccessEligibleIdentity(
                tenant, identityId);

        var roleAvailability = catalogAvailability.currentAvailability(
                        tenant, CatalogAccessTargetKind.ROLE, roleId)
                .orElseThrow(() -> new AccessAssignmentCommandException(
                        "role_not_found",
                        "The requested Role was not found."));
        if (!roleAvailability.assignable()) {
            throw new AccessAssignmentCommandException(
                    roleAvailability.terminal()
                            ? "role_unavailable_terminal"
                            : "role_unavailable_temporary",
                    "The requested Role is not currently assignable.");
        }

        var expansion = roleExpansion.expandCurrent(tenant, roleId);
        if (expansion.status() != RoleExpansionQuery.Status.AVAILABLE
                || expansion.paths().isEmpty()) {
            throw new AccessAssignmentCommandException(
                    "role_not_assignable",
                    "The requested Role has no valid current expansion.");
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
            var targetIds = expansion.paths().stream()
                    .map(RoleExpansionQuery.EntitlementPath::applicationTargetId)
                    .distinct()
                    .toList();
            if (targetIds.size() != 1) {
                throw new AccessAssignmentCommandException(
                        "specific_role_target_ambiguous",
                        "SPECIFIC principal constraint requires a Role expansion with exactly one ApplicationTarget.");
            }
            if (!targetIds.getFirst().equals(
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
                identityStatus.accessEligibilityEpoch(),
                AccessAssignment.TargetKind.ROLE,
                roleId,
                null,
                principalConstraintKind,
                specificPrincipalId,
                provenanceKind,
                provenanceRefId,
                lifecycleState,
                validFrom,
                validUntil,
                1,
                now,
                now);

        return transactions.required(() -> {
            assignments.insert(tenant, assignment);
            facts.projectionInputChanged(
                    tenant, assignment, correlationId, causationId);
            boundaries.scheduleBoundaries(tenant, assignment, now);
            return assignments.findById(tenant, assignment.id())
                    .orElseThrow(() -> new IllegalStateException(
                            "created AccessAssignment could not be reloaded"));
        });
    }

    public AccessAssignment suspend(
            TenantContext tenant,
            UUID assignmentId,
            long expectedRevision,
            Instant now) {
        return transition(tenant, assignmentId, expectedRevision, now, Action.SUSPEND);
    }

    public AccessAssignment resume(
            TenantContext tenant,
            UUID assignmentId,
            long expectedRevision,
            Instant now) {
        return transition(tenant, assignmentId, expectedRevision, now, Action.RESUME);
    }

    public AccessAssignment cancel(
            TenantContext tenant,
            UUID assignmentId,
            long expectedRevision,
            Instant now) {
        return transition(tenant, assignmentId, expectedRevision, now, Action.CANCEL);
    }

    public AccessAssignment revoke(
            TenantContext tenant,
            UUID assignmentId,
            long expectedRevision,
            Instant now) {
        return transition(tenant, assignmentId, expectedRevision, now, Action.REVOKE);
    }

    private AccessAssignment transition(
            TenantContext tenant,
            UUID assignmentId,
            long expectedRevision,
            Instant now,
            Action action) {
        Objects.requireNonNull(now, "now");
        return transactions.required(() -> {
            AccessAssignment current = current(tenant, assignmentId, expectedRevision);
            if (action == Action.RESUME) {
                requireCurrentIdentityEpoch(tenant, current);
            }
            AccessAssignment.LifecycleState next;
            try {
                next = AccessAssignmentLifecycleDefinition.transition(current, action, now);
            } catch (AccessAssignmentLifecycleDefinition.Rejection rejected) {
                throw new AccessAssignmentCommandException(rejected.code(), rejected.getMessage());
            }
            AccessAssignment updated = assignments.updateLifecycle(
                    tenant, assignmentId, next, expectedRevision, now);
            facts.projectionInputChanged(tenant, updated);
            return updated;
        });
    }

    private IdentityAccessReferenceQuery.IdentityAccessStatus requireAccessEligibleIdentity(
            TenantContext tenant,
            UUID identityId) {
        var status = identityReferences.accessStatus(
                tenant, identityId);
        if (status.status()
                == IdentityAccessReferenceQuery.AccessStatus.NOT_FOUND) {
            throw new AccessAssignmentCommandException(
                    "identity_not_found",
                    "The requested Identity was not found in the tenant.");
        }
        if (status.status()
                != IdentityAccessReferenceQuery.AccessStatus.ACCESS_ELIGIBLE) {
            throw new AccessAssignmentCommandException(
                    "identity_not_access_eligible",
                    "The requested Identity is not access-eligible.");
        }
        return status;
    }

    private void requireCurrentIdentityEpoch(
            TenantContext tenant,
            AccessAssignment assignment) {
        var status = identityReferences.accessStatus(
                tenant, assignment.identityId());
        if (status.status()
                != IdentityAccessReferenceQuery.AccessStatus.ACCESS_ELIGIBLE) {
            throw new AccessAssignmentCommandException(
                    "identity_not_access_eligible",
                    "The AccessAssignment Identity is not currently access-eligible.");
        }
        if (status.accessEligibilityEpoch()
                != assignment.identityAccessEpoch()) {
            throw new AccessAssignmentCommandException(
                    "access_assignment_identity_epoch_stale",
                    "This AccessAssignment belongs to an earlier Identity access-eligibility era.");
        }
    }

    private AccessAssignment current(
            TenantContext tenant,
            UUID assignmentId,
            long expectedRevision) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(assignmentId, "assignmentId");
        if (expectedRevision < 1) {
            throw new IllegalArgumentException(
                    "expectedRevision must be positive");
        }
        AccessAssignment current = assignments.findById(
                        tenant, assignmentId)
                .orElseThrow(() -> new AccessAssignmentCommandException(
                        "access_assignment_not_found",
                        "The requested AccessAssignment was not found."));
        if (current.revision() != expectedRevision) {
            throw new io.wyrmgate.iam.platform.persistence.StaleWriteException(
                    "access-assignment", assignmentId, expectedRevision);
        }
        return current;
    }

    public AccessAssignment terminate(
            TenantContext tenant,
            UUID assignmentId,
            long expectedRevision,
            Instant now) {
        return terminateInternal(
                tenant, assignmentId, expectedRevision, now, null, null);
    }

    public AccessAssignment terminateLifecyclePolicyAssignment(
            TenantContext tenant,
            UUID assignmentId,
            long expectedRevision,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        return terminateInternal(
                tenant,
                assignmentId,
                expectedRevision,
                now,
                correlationId,
                causationId);
    }

    private AccessAssignment terminateInternal(
            TenantContext tenant,
            UUID assignmentId,
            long expectedRevision,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(assignmentId, "assignmentId");
        Objects.requireNonNull(now, "now");
        if (expectedRevision < 1) {
            throw new IllegalArgumentException(
                    "expectedRevision must be positive");
        }

        return transactions.required(() -> {
            AccessAssignment current = current(tenant, assignmentId, expectedRevision);
            AccessAssignment.LifecycleState terminalState;
            try {
                terminalState = AccessAssignmentLifecycleDefinition.terminate(current, now);
            } catch (AccessAssignmentLifecycleDefinition.Rejection rejected) {
                throw new AccessAssignmentCommandException(rejected.code(), rejected.getMessage());
            }
            AccessAssignment updated = assignments.terminate(
                    tenant, assignmentId, terminalState, expectedRevision, now);
            facts.projectionInputChanged(
                    tenant, updated, correlationId, causationId);
            return updated;
        });
    }

}
