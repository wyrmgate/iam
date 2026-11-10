package io.wyrmgate.iam.tenancy.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.wyrmgate.iam.platform.lifecycle.InvalidLifecycleTransitionException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantTest {

    private static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");

    @Test
    void followsCanonicalLifecycleThroughReusableKernel() {
        Tenant tenant = Tenant.provisioning(UUID.randomUUID(), "Alpha", NOW);

        tenant = tenant.transition(TenantLifecycleAction.ACTIVATE, NOW.plusSeconds(1));
        assertThat(tenant.lifecycleState()).isEqualTo(TenantLifecycleState.ACTIVE);

        tenant = tenant.transition(TenantLifecycleAction.SUSPEND, NOW.plusSeconds(2));
        assertThat(tenant.lifecycleState()).isEqualTo(TenantLifecycleState.SUSPENDED);

        tenant = tenant.transition(TenantLifecycleAction.RESUME, NOW.plusSeconds(3));
        assertThat(tenant.lifecycleState()).isEqualTo(TenantLifecycleState.ACTIVE);

        tenant = tenant.transition(
                TenantLifecycleAction.BEGIN_DECOMMISSIONING,
                NOW.plusSeconds(4));
        assertThat(tenant.lifecycleState()).isEqualTo(TenantLifecycleState.DECOMMISSIONING);

        tenant = tenant.transition(
                TenantLifecycleAction.COMPLETE_DECOMMISSIONING,
                NOW.plusSeconds(5));
        assertThat(tenant.lifecycleState()).isEqualTo(TenantLifecycleState.DECOMMISSIONED);
        assertThat(tenant.revision()).isEqualTo(6);
    }

    @Test
    void decommissionedTenantIsTerminal() {
        Tenant tenant = new Tenant(
                UUID.randomUUID(),
                "Alpha",
                TenantLifecycleState.DECOMMISSIONED,
                4,
                NOW,
                NOW.plusSeconds(3));

        assertThatThrownBy(() ->
                        tenant.transition(TenantLifecycleAction.ACTIVATE, NOW.plusSeconds(4)))
                .isInstanceOf(InvalidLifecycleTransitionException.class);
    }
}
