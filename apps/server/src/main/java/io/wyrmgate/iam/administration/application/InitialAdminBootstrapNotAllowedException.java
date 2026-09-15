package io.wyrmgate.iam.administration.application;

public final class InitialAdminBootstrapNotAllowedException extends IllegalStateException {

    public InitialAdminBootstrapNotAllowedException(String reason) {
        super("initial administrative bootstrap is not allowed: " + reason);
    }
}
