package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.catalog.application.CatalogAccessTargetAvailabilityQuery;
import io.wyrmgate.iam.catalog.domain.CatalogAccessTargetKind;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Access-owned current-assignment repair for the narrow admission/terminal-retirement race.
 *
 * <p>The normal ADR-0052 AccessTargetReduction remains the bounded bulk process for assignments
 * already present when Catalog becomes terminal. A newly committed assignment always emits normal
 * projection work; this repair re-reads current Catalog availability and terminates that one
 * assignment with ordinary Access semantics if terminal unavailability won the race.</p>
 */
public final class TerminalCatalogAssignmentRepairService {

    private final AccessAssignmentRepository assignments;
    private final CatalogAccessTargetAvailabilityQuery catalog;
    private final AccessAssignmentCommandService commands;

    public TerminalCatalogAssignmentRepairService(
            AccessAssignmentRepository assignments,
            CatalogAccessTargetAvailabilityQuery catalog,
            AccessAssignmentCommandService commands) {
        this.assignments = Objects.requireNonNull(assignments, "assignments");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    public AccessAssignment repairIfTerminal(
            TenantContext tenant,
            UUID assignmentId,
            Instant at) {
        AccessAssignment current = assignments.findById(
                        tenant, assignmentId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "AccessAssignment projection input does not exist"));
        if (terminal(current.lifecycleState())) {
            return current;
        }

        CatalogAccessTargetKind kind =
                CatalogAccessTargetKind.valueOf(
                        current.targetKind().name());
        UUID targetId =
                current.targetKind()
                                == AccessAssignment.TargetKind.ROLE
                        ? current.roleId()
                        : current.entitlementId();
        var availability = catalog.currentAvailability(
                        tenant, kind, targetId)
                .orElse(null);
        if (availability == null || !availability.terminal()) {
            return current;
        }

        try {
            return commands.terminate(
                    tenant,
                    current.id(),
                    current.revision(),
                    at);
        } catch (AccessAssignmentCommandException raced) {
            AccessAssignment reread = assignments.findById(
                            tenant, assignmentId)
                    .orElseThrow(() -> raced);
            if (terminal(reread.lifecycleState())) {
                return reread;
            }
            throw raced;
        }
    }

    private static boolean terminal(
            AccessAssignment.LifecycleState state) {
        return state == AccessAssignment.LifecycleState.REVOKED
                || state == AccessAssignment.LifecycleState.EXPIRED
                || state == AccessAssignment.LifecycleState.CANCELLED;
    }
}
