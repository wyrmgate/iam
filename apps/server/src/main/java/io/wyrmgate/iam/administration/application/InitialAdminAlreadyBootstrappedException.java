package io.wyrmgate.iam.administration.application;

import java.util.UUID;

public final class InitialAdminAlreadyBootstrappedException extends IllegalStateException {

    public InitialAdminAlreadyBootstrappedException(UUID tenantId) {
        super("initial administrative bootstrap is permanently closed for tenant " + tenantId);
    }
}
