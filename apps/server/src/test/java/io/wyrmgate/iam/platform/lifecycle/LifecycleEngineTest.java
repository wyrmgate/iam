package io.wyrmgate.iam.platform.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LifecycleEngineTest {

    @Test
    void returnsAcceptedTransitionAfterTypedGuardPasses() {
        LifecycleEngine<State, Action, Context> engine = engine();

        LifecycleTransition<State, Action> transition =
                engine.transition(State.DRAFT, Action.ACTIVATE, new Context(true));

        assertThat(transition.fromState()).isEqualTo(State.DRAFT);
        assertThat(transition.action()).isEqualTo(Action.ACTIVATE);
        assertThat(transition.toState()).isEqualTo(State.ACTIVE);
    }

    @Test
    void propagatesCapabilityGuardFailureWithoutReinterpretingIt() {
        LifecycleEngine<State, Action, Context> engine = engine();

        assertThatThrownBy(() ->
                        engine.transition(State.DRAFT, Action.ACTIVATE, new Context(false)))
                .isInstanceOf(OwnerNotViableException.class)
                .hasMessage("owner is not viable");
    }

    @Test
    void rejectsUndefinedTransition() {
        LifecycleEngine<State, Action, Context> engine = engine();

        assertThatThrownBy(() ->
                        engine.transition(State.CLOSED, Action.ACTIVATE, new Context(true)))
                .isInstanceOfSatisfying(
                        InvalidLifecycleTransitionException.class,
                        exception -> {
                            assertThat(exception.currentState()).isEqualTo(State.CLOSED);
                            assertThat(exception.action()).isEqualTo(Action.ACTIVATE);
                        });
    }

    @Test
    void exposesOnlyStructurallyDefinedActions() {
        LifecycleEngine<State, Action, Context> engine = engine();

        assertThat(engine.isTransitionDefined(State.ACTIVE, Action.SUSPEND)).isTrue();
        assertThat(engine.isTransitionDefined(State.ACTIVE, Action.RESUME)).isFalse();
        assertThat(engine.definedActions(State.ACTIVE))
                .containsExactly(Action.SUSPEND, Action.CLOSE);
        assertThat(engine.definedActions(State.CLOSED)).isEmpty();
    }

    @Test
    void rejectsDuplicateStateActionDefinition() {
        LifecycleEngine.Builder<State, Action, Context> builder =
                LifecycleEngine.<State, Action, Context>builder()
                        .transition(State.DRAFT, Action.ACTIVATE, State.ACTIVE);

        assertThatThrownBy(() ->
                        builder.transition(State.DRAFT, Action.ACTIVATE, State.CLOSED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate lifecycle transition");
    }

    @Test
    void rejectsEmptyLifecycleDefinition() {
        assertThatThrownBy(() ->
                        LifecycleEngine.<State, Action, Context>builder().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Lifecycle definition must contain at least one transition");
    }

    private static LifecycleEngine<State, Action, Context> engine() {
        return LifecycleEngine.<State, Action, Context>builder()
                .transition(
                        State.DRAFT,
                        Action.ACTIVATE,
                        State.ACTIVE,
                        context -> {
                            if (!context.ownerViable()) {
                                throw new OwnerNotViableException("owner is not viable");
                            }
                        })
                .transition(State.ACTIVE, Action.SUSPEND, State.SUSPENDED)
                .transition(State.SUSPENDED, Action.RESUME, State.ACTIVE)
                .transition(State.ACTIVE, Action.CLOSE, State.CLOSED)
                .build();
    }

    private enum State {
        DRAFT,
        ACTIVE,
        SUSPENDED,
        CLOSED
    }

    private enum Action {
        ACTIVATE,
        SUSPEND,
        RESUME,
        CLOSE
    }

    private record Context(boolean ownerViable) {
    }

    private static final class OwnerNotViableException extends RuntimeException {
        private OwnerNotViableException(String message) {
            super(message);
        }
    }
}
