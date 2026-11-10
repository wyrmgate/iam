package io.wyrmgate.iam.platform.lifecycle;

import java.util.Objects;

/** Immutable description of one accepted lifecycle transition. */
public record LifecycleTransition<S, A>(S fromState, A action, S toState) {

    public LifecycleTransition {
        Objects.requireNonNull(fromState, "fromState");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(toState, "toState");
    }
}
