package io.wyrmgate.iam.platform.lifecycle;

/** Raised when no transition rule exists for the requested current-state/action pair. */
public final class InvalidLifecycleTransitionException extends IllegalStateException {

    private final Object currentState;
    private final Object action;

    public InvalidLifecycleTransitionException(Object currentState, Object action) {
        super("No lifecycle transition is defined for state " + currentState + " and action " + action);
        this.currentState = currentState;
        this.action = action;
    }

    public Object currentState() {
        return currentState;
    }

    public Object action() {
        return action;
    }
}
