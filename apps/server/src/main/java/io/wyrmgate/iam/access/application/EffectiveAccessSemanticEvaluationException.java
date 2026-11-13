package io.wyrmgate.iam.access.application;

/**
 * Current EffectiveAccess meaning could not be established within the bounded
 * semantic-support evaluation budget. Callers must treat this as unavailable,
 * never as absence.
 */
public final class EffectiveAccessSemanticEvaluationException
        extends RuntimeException {

    public EffectiveAccessSemanticEvaluationException() {
        super("EffectiveAccess semantic support exceeds the bounded evaluation budget");
    }
}
