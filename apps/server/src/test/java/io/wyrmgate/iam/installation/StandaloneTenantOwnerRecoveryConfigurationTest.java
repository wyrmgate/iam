package io.wyrmgate.iam.installation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.wyrmgate.iam.administration.application.StandaloneTenantOwnerRecoveryNotEligibleException;
import io.wyrmgate.iam.administration.application.StandaloneTenantOwnerRecoveryResult;
import io.wyrmgate.iam.administration.application.StandaloneTenantOwnerRecoveryService;
import io.wyrmgate.iam.audit.application.SecurityAuditPort;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.mock.env.MockEnvironment;

class StandaloneTenantOwnerRecoveryConfigurationTest {

    @Test
    void nonSealedInstallationIsRejectedBeforeAdministrationMutation() throws Exception {
        Fixture fixture = fixture(plan(InstallationBootstrapState.ADMIN_BOOTSTRAPPED));

        StandaloneTenantOwnerRecoveryNotEligibleException exception = assertThrows(
                StandaloneTenantOwnerRecoveryNotEligibleException.class,
                () -> fixture.runner.run(mock(ApplicationArguments.class)));

        assertEquals("installation_not_sealed", exception.reason());
        verify(fixture.recoveryService, never()).recover(any(), any(), any(), any(), any());
        verify(fixture.audit).append(any(), any());
    }

    @Test
    void recoveryCommandRequiresNonWebProcess() throws Exception {
        InstallationBootstrapRepository repository = mock(InstallationBootstrapRepository.class);
        StandaloneTenantOwnerRecoveryService recovery = mock(StandaloneTenantOwnerRecoveryService.class);
        SecurityAuditPort audit = mock(SecurityAuditPort.class);
        IdGenerator ids = mock(IdGenerator.class);
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.main.web-application-type", "servlet");

        ApplicationRunner runner = new StandaloneTenantOwnerRecoveryConfiguration()
                .standaloneTenantOwnerRecoveryRunner(environment, repository, recovery, audit, ids);

        StandaloneTenantOwnerRecoveryNotEligibleException exception = assertThrows(
                StandaloneTenantOwnerRecoveryNotEligibleException.class,
                () -> runner.run(mock(ApplicationArguments.class)));

        assertEquals("recovery_requires_spring.main.web-application-type=none", exception.reason());
        verify(repository, never()).find();
        verify(recovery, never()).recover(any(), any(), any(), any(), any());
    }

    @Test
    void sealedInstallationDerivesAllRecoveryIdentifiersFromDurablePlan() throws Exception {
        InstallationBootstrapPlan plan = plan(InstallationBootstrapState.SEALED);
        Fixture fixture = fixture(plan);
        UUID roleId = UUID.randomUUID();
        when(fixture.recoveryService.recover(
                        new TenantContext(plan.tenantId()),
                        plan.identityId(),
                        plan.principalId(),
                        plan.correlationId(),
                        fixture.now))
                .thenReturn(new StandaloneTenantOwnerRecoveryResult(
                        StandaloneTenantOwnerRecoveryResult.Status.ALREADY_COMPLETE,
                        roleId));

        fixture.runner.run(mock(ApplicationArguments.class));

        verify(fixture.recoveryService).recover(
                new TenantContext(plan.tenantId()),
                plan.identityId(),
                plan.principalId(),
                plan.correlationId(),
                fixture.now);
        verify(fixture.audit).append(any(), any());
    }

    private static Fixture fixture(InstallationBootstrapPlan plan) {
        InstallationBootstrapRepository repository = mock(InstallationBootstrapRepository.class);
        when(repository.find()).thenReturn(Optional.of(plan));
        StandaloneTenantOwnerRecoveryService recovery = mock(StandaloneTenantOwnerRecoveryService.class);
        SecurityAuditPort audit = mock(SecurityAuditPort.class);
        IdGenerator ids = mock(IdGenerator.class);
        when(ids.nextId()).thenReturn(UUID.randomUUID());
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.main.web-application-type", "none");
        Instant now = Instant.parse("2026-10-16T00:00:00Z");

        // The runner owns its clock at invocation time; make a fixture whose expected instant is
        // captured by matching the actual service call rather than exposing a clock property.
        ApplicationRunner runner = new StandaloneTenantOwnerRecoveryConfiguration()
                .standaloneTenantOwnerRecoveryRunner(environment, repository, recovery, audit, ids);
        return new Fixture(repository, recovery, audit, runner, now);
    }

    private static InstallationBootstrapPlan plan(InstallationBootstrapState state) {
        Instant started = Instant.parse("2026-10-01T00:00:00Z");
        Instant updated = Instant.parse("2026-10-01T01:00:00Z");
        return new InstallationBootstrapPlan(
                state,
                UUID.randomUUID(),
                "Tenant",
                UUID.randomUUID(),
                "First Administrator",
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "wyrmgate-console",
                "http://localhost/callback",
                UUID.randomUUID(),
                "first-admin",
                UUID.randomUUID(),
                UUID.randomUUID(),
                InstallationBootstrapService.BOOTSTRAP_VERSION,
                started,
                updated,
                state == InstallationBootstrapState.SEALED ? updated : null);
    }

    private record Fixture(
            InstallationBootstrapRepository repository,
            StandaloneTenantOwnerRecoveryService recoveryService,
            SecurityAuditPort audit,
            ApplicationRunner runner,
            Instant now) {}
}
