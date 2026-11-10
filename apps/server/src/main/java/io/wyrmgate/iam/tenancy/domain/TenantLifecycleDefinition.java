package io.wyrmgate.iam.tenancy.domain;

import io.wyrmgate.iam.platform.lifecycle.LifecycleEngine;

/** Typed transition definition for the Tenant aggregate. */
final class TenantLifecycleDefinition {

    private static final LifecycleEngine<TenantLifecycleState, TenantLifecycleAction, Void> ENGINE =
            LifecycleEngine.<TenantLifecycleState, TenantLifecycleAction, Void>builder()
                    .transition(
                            TenantLifecycleState.PROVISIONING,
                            TenantLifecycleAction.ACTIVATE,
                            TenantLifecycleState.ACTIVE)
                    .transition(
                            TenantLifecycleState.ACTIVE,
                            TenantLifecycleAction.SUSPEND,
                            TenantLifecycleState.SUSPENDED)
                    .transition(
                            TenantLifecycleState.SUSPENDED,
                            TenantLifecycleAction.RESUME,
                            TenantLifecycleState.ACTIVE)
                    .transition(
                            TenantLifecycleState.ACTIVE,
                            TenantLifecycleAction.BEGIN_DECOMMISSIONING,
                            TenantLifecycleState.DECOMMISSIONING)
                    .transition(
                            TenantLifecycleState.SUSPENDED,
                            TenantLifecycleAction.BEGIN_DECOMMISSIONING,
                            TenantLifecycleState.DECOMMISSIONING)
                    .transition(
                            TenantLifecycleState.DECOMMISSIONING,
                            TenantLifecycleAction.COMPLETE_DECOMMISSIONING,
                            TenantLifecycleState.DECOMMISSIONED)
                    .build();

    private TenantLifecycleDefinition() {
    }

    static TenantLifecycleState transition(
            TenantLifecycleState currentState,
            TenantLifecycleAction action) {
        return ENGINE.transition(currentState, action, null).toState();
    }
}
