package io.wyrmgate.iam.installation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InstallationSealedBootstrapQueryAdapterTest {

    @Test
    void acceptsOnlyExactSealedInstallationLineage() {
        UUID tenantId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        UUID principalId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        InstallationBootstrapPlan sealed = plan(
                InstallationBootstrapState.SEALED,
                tenantId,
                identityId,
                principalId,
                correlationId);
        InstallationSealedBootstrapQueryAdapter query =
                new InstallationSealedBootstrapQueryAdapter(new FixedRepository(sealed));

        assertTrue(query.matchesSealedInstallation(
                new TenantContext(tenantId), identityId, principalId, correlationId));
        assertFalse(query.matchesSealedInstallation(
                new TenantContext(UUID.randomUUID()), identityId, principalId, correlationId));
        assertFalse(query.matchesSealedInstallation(
                new TenantContext(tenantId), UUID.randomUUID(), principalId, correlationId));
        assertFalse(query.matchesSealedInstallation(
                new TenantContext(tenantId), identityId, UUID.randomUUID(), correlationId));
        assertFalse(query.matchesSealedInstallation(
                new TenantContext(tenantId), identityId, principalId, UUID.randomUUID()));
    }

    @Test
    void rejectsUnsealedInstallation() {
        UUID tenantId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        UUID principalId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        InstallationBootstrapPlan unsealed = plan(
                InstallationBootstrapState.VERIFIED,
                tenantId,
                identityId,
                principalId,
                correlationId);
        InstallationSealedBootstrapQueryAdapter query =
                new InstallationSealedBootstrapQueryAdapter(new FixedRepository(unsealed));

        assertFalse(query.matchesSealedInstallation(
                new TenantContext(tenantId), identityId, principalId, correlationId));
    }

    private static InstallationBootstrapPlan plan(
            InstallationBootstrapState state,
            UUID tenantId,
            UUID identityId,
            UUID principalId,
            UUID correlationId) {
        Instant started = Instant.parse("2026-10-01T00:00:00Z");
        Instant updated = Instant.parse("2026-10-01T00:10:00Z");
        return new InstallationBootstrapPlan(
                state,
                tenantId,
                "Tenant",
                identityId,
                "Owner",
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "wyrmgate-console",
                "https://iam.example/callback",
                principalId,
                "admin",
                UUID.randomUUID(),
                correlationId,
                1,
                started,
                updated,
                state == InstallationBootstrapState.SEALED ? updated : null);
    }

    private record FixedRepository(InstallationBootstrapPlan plan)
            implements InstallationBootstrapRepository {

        @Override
        public Optional<InstallationBootstrapPlan> find() {
            return Optional.of(plan);
        }

        @Override
        public boolean createPlan(InstallationBootstrapPlan ignored) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean advance(
                InstallationBootstrapState expected,
                InstallationBootstrapState next,
                Instant now) {
            throw new UnsupportedOperationException();
        }
    }
}
