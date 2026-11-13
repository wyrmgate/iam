package io.wyrmgate.iam.access.domain;

import io.wyrmgate.iam.access.domain.AccessAssignment.LifecycleState;
import io.wyrmgate.iam.platform.lifecycle.InvalidLifecycleTransitionException;
import io.wyrmgate.iam.platform.lifecycle.LifecycleEngine;
import java.time.Instant;
import java.util.Objects;

/**
 * Access-owned assignment transition rules, using the shared Platform lifecycle kernel.
 *
 * <p>Temporal validity is independent of stored lifecycle. A due SCHEDULED assignment
 * can be reduced without waiting for a boundary worker.</p>
 */
public final class AccessAssignmentLifecycleDefinition {

    public enum Action {
        SUSPEND, RESUME, CANCEL, REVOKE, EXPIRE
    }

    private record Context(AccessAssignment assignment, Instant at) {}

    private static final LifecycleEngine<LifecycleState, Action, Context> ENGINE =
            LifecycleEngine.<LifecycleState, Action, Context>builder()
                    .transition(LifecycleState.ACTIVE, Action.SUSPEND,
                            LifecycleState.SUSPENDED, AccessAssignmentLifecycleDefinition::requireNotEnded)
                    .transition(LifecycleState.SCHEDULED, Action.SUSPEND,
                            LifecycleState.SUSPENDED, AccessAssignmentLifecycleDefinition::requireStartedForSuspend,
                            AccessAssignmentLifecycleDefinition::requireNotEnded)
                    .transition(LifecycleState.SUSPENDED, Action.RESUME,
                            LifecycleState.ACTIVE, AccessAssignmentLifecycleDefinition::requireNotEnded,
                            AccessAssignmentLifecycleDefinition::requireStarted)
                    .transition(LifecycleState.SCHEDULED, Action.CANCEL,
                            LifecycleState.CANCELLED, AccessAssignmentLifecycleDefinition::requireFuture)
                    .transition(LifecycleState.ACTIVE, Action.REVOKE,
                            LifecycleState.REVOKED, AccessAssignmentLifecycleDefinition::requireNotEnded)
                    .transition(LifecycleState.SUSPENDED, Action.REVOKE,
                            LifecycleState.REVOKED, AccessAssignmentLifecycleDefinition::requireNotEnded)
                    .transition(LifecycleState.SCHEDULED, Action.REVOKE,
                            LifecycleState.REVOKED, AccessAssignmentLifecycleDefinition::requireStartedForRevoke,
                            AccessAssignmentLifecycleDefinition::requireNotEnded)
                    .transition(LifecycleState.SCHEDULED, Action.EXPIRE,
                            LifecycleState.EXPIRED, AccessAssignmentLifecycleDefinition::requireEnded)
                    .transition(LifecycleState.ACTIVE, Action.EXPIRE,
                            LifecycleState.EXPIRED, AccessAssignmentLifecycleDefinition::requireEnded)
                    .transition(LifecycleState.SUSPENDED, Action.EXPIRE,
                            LifecycleState.EXPIRED, AccessAssignmentLifecycleDefinition::requireEnded)
                    .build();

    private AccessAssignmentLifecycleDefinition() {}

    public static LifecycleState transition(
            AccessAssignment assignment, Action action, Instant at) {
        Objects.requireNonNull(assignment, "assignment");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(at, "at");
        try {
            return ENGINE.transition(
                    assignment.lifecycleState(), action, new Context(assignment, at)).toState();
        } catch (InvalidLifecycleTransitionException invalid) {
            throw invalidAction(action);
        }
    }

    /** Select the ordinary terminal outcome for an Access-owned reduction. */
    public static LifecycleState terminate(AccessAssignment assignment, Instant at) {
        Objects.requireNonNull(assignment, "assignment");
        Objects.requireNonNull(at, "at");
        if (isTerminal(assignment.lifecycleState())) {
            throw new Rejection("access_assignment_terminal",
                    "The AccessAssignment is already terminal.");
        }
        if (ended(new Context(assignment, at))) {
            return transition(assignment, Action.EXPIRE, at);
        }
        if (assignment.lifecycleState() == LifecycleState.SCHEDULED
                && at.isBefore(assignment.validFrom())) {
            return transition(assignment, Action.CANCEL, at);
        }
        return transition(assignment, Action.REVOKE, at);
    }

    private static boolean isTerminal(LifecycleState state) {
        return state == LifecycleState.REVOKED
                || state == LifecycleState.EXPIRED
                || state == LifecycleState.CANCELLED;
    }

    private static boolean ended(Context context) {
        Instant until = context.assignment().validUntil();
        return until != null && !context.at().isBefore(until);
    }

    private static boolean started(Context context) {
        Instant from = context.assignment().validFrom();
        return from == null || !context.at().isBefore(from);
    }

    private static void requireNotEnded(Context context) {
        if (ended(context)) {
            throw new Rejection("access_assignment_expired",
                    "The AccessAssignment validity has already ended.");
        }
    }

    private static void requireStarted(Context context) {
        if (!started(context)) {
            throw new Rejection("access_assignment_not_yet_valid",
                    "The AccessAssignment validity has not started.");
        }
    }

    private static void requireStartedForSuspend(Context context) {
        if (!started(context)) {
            throw invalidAction(Action.SUSPEND);
        }
    }

    private static void requireStartedForRevoke(Context context) {
        if (!started(context)) {
            throw invalidAction(Action.REVOKE);
        }
    }

    private static void requireFuture(Context context) {
        if (started(context)) {
            throw invalidAction(Action.CANCEL);
        }
    }

    private static void requireEnded(Context context) {
        if (!ended(context)) {
            throw new Rejection("access_assignment_not_expired",
                    "The AccessAssignment validity has not ended.");
        }
    }

    private static Rejection invalidAction(Action action) {
        return switch (action) {
            case SUSPEND -> new Rejection("access_assignment_not_active",
                    "Only an ACTIVE or currently valid SCHEDULED AccessAssignment can be suspended.");
            case RESUME -> new Rejection("access_assignment_not_suspended",
                    "Only a SUSPENDED AccessAssignment can be resumed.");
            case CANCEL -> new Rejection("access_assignment_not_cancellable",
                    "Only a future SCHEDULED AccessAssignment can be cancelled.");
            case REVOKE -> new Rejection("access_assignment_not_revocable",
                    "Only ACTIVE, SUSPENDED or currently valid SCHEDULED AccessAssignment can be revoked.");
            case EXPIRE -> new Rejection("access_assignment_terminal",
                    "The AccessAssignment is already terminal.");
        };
    }

    /** Typed domain rejection; application adapters retain their semantic error contract. */
    public static final class Rejection extends IllegalStateException {
        private final String code;

        private Rejection(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
