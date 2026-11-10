package io.wyrmgate.iam.platform.lifecycle;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Stateless, framework-neutral transition kernel for capability-owned lifecycle state machines.
 *
 * <p>The engine validates that a transition exists, invokes capability-supplied typed guards, and
 * returns the accepted transition. It does not mutate aggregates, persist state, schedule work,
 * publish facts, or interpret business semantics.</p>
 */
public final class LifecycleEngine<S, A, C> {

    private final Map<TransitionKey<S, A>, TransitionRule<S, C>> rules;

    private LifecycleEngine(Map<TransitionKey<S, A>, TransitionRule<S, C>> rules) {
        this.rules = Collections.unmodifiableMap(new LinkedHashMap<>(rules));
    }

    public LifecycleTransition<S, A> transition(S currentState, A action, C context) {
        Objects.requireNonNull(currentState, "currentState");
        Objects.requireNonNull(action, "action");

        TransitionRule<S, C> rule = rules.get(new TransitionKey<>(currentState, action));
        if (rule == null) {
            throw new InvalidLifecycleTransitionException(currentState, action);
        }

        for (LifecycleGuard<C> guard : rule.guards()) {
            guard.validate(context);
        }

        return new LifecycleTransition<>(currentState, action, rule.targetState());
    }

    public boolean isTransitionDefined(S currentState, A action) {
        Objects.requireNonNull(currentState, "currentState");
        Objects.requireNonNull(action, "action");
        return rules.containsKey(new TransitionKey<>(currentState, action));
    }

    public Set<A> definedActions(S currentState) {
        Objects.requireNonNull(currentState, "currentState");
        LinkedHashSet<A> actions = new LinkedHashSet<>();
        for (TransitionKey<S, A> key : rules.keySet()) {
            if (key.currentState().equals(currentState)) {
                actions.add(key.action());
            }
        }
        return Collections.unmodifiableSet(actions);
    }

    public static <S, A, C> Builder<S, A, C> builder() {
        return new Builder<>();
    }

    public static final class Builder<S, A, C> {

        private final Map<TransitionKey<S, A>, TransitionRule<S, C>> rules = new LinkedHashMap<>();

        private Builder() {
        }

        @SafeVarargs
        public final Builder<S, A, C> transition(
                S fromState,
                A action,
                S toState,
                LifecycleGuard<C>... guards) {
            Objects.requireNonNull(fromState, "fromState");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(toState, "toState");
            Objects.requireNonNull(guards, "guards");

            List<LifecycleGuard<C>> guardList = List.of(guards);
            TransitionKey<S, A> key = new TransitionKey<>(fromState, action);
            TransitionRule<S, C> previous = rules.putIfAbsent(
                    key,
                    new TransitionRule<>(toState, guardList));
            if (previous != null) {
                throw new IllegalArgumentException(
                        "Duplicate lifecycle transition for state " + fromState + " and action " + action);
            }
            return this;
        }

        public LifecycleEngine<S, A, C> build() {
            if (rules.isEmpty()) {
                throw new IllegalStateException("Lifecycle definition must contain at least one transition");
            }
            return new LifecycleEngine<>(rules);
        }
    }

    private record TransitionKey<S, A>(S currentState, A action) {
    }

    private record TransitionRule<S, C>(S targetState, List<LifecycleGuard<C>> guards) {
        private TransitionRule {
            Objects.requireNonNull(targetState, "targetState");
            guards = List.copyOf(guards);
        }
    }
}
