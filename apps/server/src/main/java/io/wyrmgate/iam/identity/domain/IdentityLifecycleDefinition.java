package io.wyrmgate.iam.identity.domain;

import io.wyrmgate.iam.platform.lifecycle.InvalidLifecycleTransitionException;
import io.wyrmgate.iam.platform.lifecycle.LifecycleEngine;

/**
 * Identity-owned lifecycle definition backed by the reusable platform transition kernel.
 *
 * <p>The kernel validates transition mechanics only. Identity retains the state/action vocabulary
 * and business transition graph.</p>
 */
public final class IdentityLifecycleDefinition {

    private static final LifecycleEngine<IdentityLifecycleState, IdentityLifecycleAction, Void> ENGINE =
            LifecycleEngine.<IdentityLifecycleState, IdentityLifecycleAction, Void>builder()
                    .transition(
                            IdentityLifecycleState.PENDING,
                            IdentityLifecycleAction.ACTIVATE,
                            IdentityLifecycleState.ACTIVE)
                    .transition(
                            IdentityLifecycleState.PENDING,
                            IdentityLifecycleAction.DEACTIVATE,
                            IdentityLifecycleState.INACTIVE)
                    .transition(
                            IdentityLifecycleState.PENDING,
                            IdentityLifecycleAction.DECOMMISSION,
                            IdentityLifecycleState.DECOMMISSIONED)
                    .transition(
                            IdentityLifecycleState.ACTIVE,
                            IdentityLifecycleAction.SUSPEND,
                            IdentityLifecycleState.SUSPENDED)
                    .transition(
                            IdentityLifecycleState.ACTIVE,
                            IdentityLifecycleAction.DEACTIVATE,
                            IdentityLifecycleState.INACTIVE)
                    .transition(
                            IdentityLifecycleState.ACTIVE,
                            IdentityLifecycleAction.DECOMMISSION,
                            IdentityLifecycleState.DECOMMISSIONED)
                    .transition(
                            IdentityLifecycleState.SUSPENDED,
                            IdentityLifecycleAction.RESUME,
                            IdentityLifecycleState.ACTIVE)
                    .transition(
                            IdentityLifecycleState.SUSPENDED,
                            IdentityLifecycleAction.DEACTIVATE,
                            IdentityLifecycleState.INACTIVE)
                    .transition(
                            IdentityLifecycleState.SUSPENDED,
                            IdentityLifecycleAction.DECOMMISSION,
                            IdentityLifecycleState.DECOMMISSIONED)
                    .transition(
                            IdentityLifecycleState.INACTIVE,
                            IdentityLifecycleAction.ACTIVATE,
                            IdentityLifecycleState.ACTIVE)
                    .transition(
                            IdentityLifecycleState.INACTIVE,
                            IdentityLifecycleAction.DECOMMISSION,
                            IdentityLifecycleState.DECOMMISSIONED)
                    .build();

    private IdentityLifecycleDefinition() {
    }

    public static IdentityLifecycleState transitionTo(
            IdentityLifecycleState currentState,
            IdentityLifecycleState targetState) {
        if (currentState == IdentityLifecycleState.DECOMMISSIONED) {
            throw new IllegalStateException("DECOMMISSIONED Identity is terminal");
        }

        IdentityLifecycleAction action = actionFor(currentState, targetState);
        try {
            return ENGINE.transition(currentState, action, null).toState();
        } catch (InvalidLifecycleTransitionException exception) {
            throw new IllegalArgumentException(
                    "invalid Identity lifecycle transition " + currentState + " -> " + targetState,
                    exception);
        }
    }

    private static IdentityLifecycleAction actionFor(
            IdentityLifecycleState currentState,
            IdentityLifecycleState targetState) {
        return switch (targetState) {
            case ACTIVE -> currentState == IdentityLifecycleState.SUSPENDED
                    ? IdentityLifecycleAction.RESUME
                    : IdentityLifecycleAction.ACTIVATE;
            case SUSPENDED -> IdentityLifecycleAction.SUSPEND;
            case INACTIVE -> IdentityLifecycleAction.DEACTIVATE;
            case DECOMMISSIONED -> IdentityLifecycleAction.DECOMMISSION;
            case PENDING -> throw new IllegalArgumentException(
                    "invalid Identity lifecycle transition " + currentState + " -> " + targetState);
        };
    }
}
