package io.wyrmgate.iam.tenancy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import io.wyrmgate.iam.tenancy.domain.Tenant;
import io.wyrmgate.iam.tenancy.domain.TenantLifecycleState;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class TenantCommandServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");

    @Test
    void freshInstallCreatesProvisioningTenantThenActivatesAfterOwnerGuard() {
        UUID tenantId = UUID.randomUUID();
        InMemoryRepository repository = new InMemoryRepository();
        RecordingContinuityGuard guard = new RecordingContinuityGuard();
        TenantCommandService service = service(repository, guard);

        Tenant created = service.ensureFirstTenant(tenantId, " Alpha ", NOW);
        assertThat(created.lifecycleState()).isEqualTo(TenantLifecycleState.PROVISIONING);
        assertThat(created.displayName()).isEqualTo("Alpha");
        assertThat(created.revision()).isEqualTo(1);

        Tenant active = service.activate(tenantId, 1, NOW.plusSeconds(1));
        assertThat(active.lifecycleState()).isEqualTo(TenantLifecycleState.ACTIVE);
        assertThat(active.revision()).isEqualTo(2);
        assertThat(guard.activationChecks).isEqualTo(1);
    }

    @Test
    void activationFailsClosedWhenAdministrationRejectsOwnerViability() {
        UUID tenantId = UUID.randomUUID();
        InMemoryRepository repository = new InMemoryRepository();
        RecordingContinuityGuard guard = new RecordingContinuityGuard();
        guard.failActivation = true;
        TenantCommandService service = service(repository, guard);
        service.ensureFirstTenant(tenantId, "Alpha", NOW);

        assertThatThrownBy(() ->
                        service.activate(tenantId, 1, NOW.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("owner viability");

        assertThat(repository.tenant.lifecycleState())
                .isEqualTo(TenantLifecycleState.PROVISIONING);
        assertThat(repository.tenant.revision()).isEqualTo(1);
    }

    @Test
    void suspensionUsesPlatformOwnerContinuityGuard() {
        UUID tenantId = UUID.randomUUID();
        InMemoryRepository repository = new InMemoryRepository();
        RecordingContinuityGuard guard = new RecordingContinuityGuard();
        TenantCommandService service = service(repository, guard);
        service.ensureFirstTenant(tenantId, "Alpha", NOW);
        service.activate(tenantId, 1, NOW.plusSeconds(1));

        Tenant suspended = service.suspend(tenantId, 2, NOW.plusSeconds(2));

        assertThat(suspended.lifecycleState()).isEqualTo(TenantLifecycleState.SUSPENDED);
        assertThat(guard.restrictionChecks).isEqualTo(1);
    }

    @Test
    void invalidLifecycleIsRejectedBeforeCrossCapabilityGuard() {
        UUID tenantId = UUID.randomUUID();
        InMemoryRepository repository = new InMemoryRepository();
        RecordingContinuityGuard guard = new RecordingContinuityGuard();
        TenantCommandService service = service(repository, guard);
        service.ensureFirstTenant(tenantId, "Alpha", NOW);

        assertThatThrownBy(() ->
                        service.suspend(tenantId, 1, NOW.plusSeconds(1)))
                .isInstanceOf(io.wyrmgate.iam.platform.lifecycle.InvalidLifecycleTransitionException.class);

        assertThat(guard.restrictionChecks).isZero();
    }

    private static TenantCommandService service(
            InMemoryRepository repository,
            RecordingContinuityGuard guard) {
        return new TenantCommandService(
                repository,
                guard,
                new ImmediateTransactionExecutor());
    }

    private static final class InMemoryRepository implements TenantRepository {
        private Tenant tenant;

        @Override
        public long count() {
            return tenant == null ? 0 : 1;
        }

        @Override
        public Optional<Tenant> find(UUID tenantId) {
            return tenant != null && tenant.id().equals(tenantId)
                    ? Optional.of(tenant)
                    : Optional.empty();
        }

        @Override
        public void insert(Tenant tenant) {
            this.tenant = tenant;
        }

        @Override
        public Tenant updateLifecycle(Tenant tenant, long expectedRevision) {
            if (this.tenant == null || this.tenant.revision() != expectedRevision) {
                throw new AssertionError("unexpected revision");
            }
            this.tenant = tenant;
            return tenant;
        }
    }

    private static final class RecordingContinuityGuard
            implements TenantLifecycleContinuityGuard {
        private int activationChecks;
        private int restrictionChecks;
        private boolean failActivation;

        @Override
        public void assertMayBecomeActive(TenantContext tenant, Instant at) {
            activationChecks++;
            if (failActivation) {
                throw new IllegalStateException("owner viability unavailable");
            }
        }

        @Override
        public void assertMaySuspendOrDecommission(TenantContext tenant, Instant at) {
            restrictionChecks++;
        }
    }

    private static final class ImmediateTransactionExecutor
            implements TransactionExecutor {
        @Override
        public <T> T required(Supplier<T> work) {
            return work.get();
        }
    }
}
