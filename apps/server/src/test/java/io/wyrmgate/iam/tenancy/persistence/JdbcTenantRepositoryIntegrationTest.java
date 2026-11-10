package io.wyrmgate.iam.tenancy.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.wyrmgate.iam.platform.persistence.SharedPostgresIntegrationDatabase;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.tenancy.domain.Tenant;
import io.wyrmgate.iam.tenancy.domain.TenantLifecycleAction;
import io.wyrmgate.iam.tenancy.domain.TenantLifecycleState;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcTenantRepositoryIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");

    private static JdbcTenantRepository repository;

    @BeforeAll
    static void resetDatabase() {
        SharedPostgresIntegrationDatabase.reset();
        repository = new JdbcTenantRepository(
                new JdbcTemplate(SharedPostgresIntegrationDatabase.dataSource()));
    }

    @Test
    void persistsCanonicalLifecycleWithOptimisticRevision() {
        UUID tenantId = UUID.randomUUID();
        Tenant provisioning = Tenant.provisioning(tenantId, "Tenant A", NOW);
        repository.insert(provisioning);

        Tenant active = provisioning.transition(
                TenantLifecycleAction.ACTIVATE,
                NOW.plusSeconds(1));
        Tenant persisted = repository.updateLifecycle(active, 1);

        assertThat(persisted.lifecycleState()).isEqualTo(TenantLifecycleState.ACTIVE);
        assertThat(persisted.revision()).isEqualTo(2);

        Tenant stale = persisted.transition(
                TenantLifecycleAction.SUSPEND,
                NOW.plusSeconds(2));
        assertThatThrownBy(() -> repository.updateLifecycle(stale, 1))
                .isInstanceOf(StaleWriteException.class);

        assertThat(repository.find(tenantId).orElseThrow().lifecycleState())
                .isEqualTo(TenantLifecycleState.ACTIVE);
    }
}
