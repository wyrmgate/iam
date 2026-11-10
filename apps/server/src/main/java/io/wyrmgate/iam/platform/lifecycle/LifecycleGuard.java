package io.wyrmgate.iam.platform.lifecycle;

/**
 * Capability-supplied guard for one lifecycle transition.
 *
 * <p>The guard may inspect typed transition context and reject the transition by throwing a
 * capability-specific runtime exception. The lifecycle kernel does not interpret business
 * meaning or translate guard failures.</p>
 */
@FunctionalInterface
public interface LifecycleGuard<C> {

    void validate(C context);
}
