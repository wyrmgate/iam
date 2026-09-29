package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Access-owned review remediation application.
 *
 * <p>Re-reads current authoritative assignment state and persists the causal
 * remediation result in the same Access transaction.</p>
 */
public final class AccessReviewRemediationCommandService
        implements AccessReviewRemediationCommand {

    private final AccessAssignmentRepository assignments;
    private final AccessAssignmentCommandService commands;
    private final AccessReviewRemediationRepository applications;
    private final TransactionExecutor transactions;

    public AccessReviewRemediationCommandService(
            AccessAssignmentRepository assignments,
            AccessAssignmentCommandService commands,
            AccessReviewRemediationRepository applications,
            TransactionExecutor transactions) {
        this.assignments = Objects.requireNonNull(
                assignments, "assignments");
        this.commands = Objects.requireNonNull(
                commands, "commands");
        this.applications = Objects.requireNonNull(
                applications, "applications");
        this.transactions = Objects.requireNonNull(
                transactions, "transactions");
    }

    @Override
    public Result apply(
            TenantContext tenant,
            UUID reviewRemediationId,
            UUID accessAssignmentId,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(
                reviewRemediationId, "reviewRemediationId");
        Objects.requireNonNull(
                accessAssignmentId, "accessAssignmentId");
        Objects.requireNonNull(now, "now");

        return transactions.required(() -> {
            var replay = applications.find(
                    tenant, reviewRemediationId);
            if (replay.isPresent()) {
                if (!replay.get().accessAssignmentId()
                        .equals(accessAssignmentId)) {
                    throw new IllegalStateException(
                            "review remediation id was already applied to another AccessAssignment");
                }
                return replay.get();
            }

            AccessAssignment current =
                    assignments.findById(
                            tenant, accessAssignmentId)
                            .orElse(null);
            Result result;
            if (current == null
                    || isTerminal(current.lifecycleState())) {
                result = new Result(
                        accessAssignmentId,
                        Outcome.NO_ACTION_REQUIRED,
                        current == null
                                ? "NOT_FOUND"
                                : current.lifecycleState().name());
            } else {
                AccessAssignment terminated =
                        commands.terminate(
                                tenant,
                                accessAssignmentId,
                                current.revision(),
                                now);
                result = new Result(
                        accessAssignmentId,
                        Outcome.APPLIED,
                        terminated.lifecycleState().name());
            }

            applications.insert(
                    tenant,
                    reviewRemediationId,
                    accessAssignmentId,
                    result,
                    now);
            return result;
        });
    }

    private static boolean isTerminal(
            AccessAssignment.LifecycleState state) {
        return state
                == AccessAssignment.LifecycleState.REVOKED
                || state
                == AccessAssignment.LifecycleState.EXPIRED
                || state
                == AccessAssignment.LifecycleState.CANCELLED;
    }
}
