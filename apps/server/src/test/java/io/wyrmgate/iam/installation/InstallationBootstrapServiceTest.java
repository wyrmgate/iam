package io.wyrmgate.iam.installation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.wyrmgate.iam.administration.application.InitialAdminBootstrapService;
import io.wyrmgate.iam.catalog.application.CatalogCommandService;
import io.wyrmgate.iam.catalog.application.SsoClientProtocolQuery;
import io.wyrmgate.iam.catalog.application.SsoClientRegistrationService;
import io.wyrmgate.iam.catalog.domain.SsoClientRegistration;
import io.wyrmgate.iam.credential.application.CredentialAuthenticationService;
import io.wyrmgate.iam.credential.application.PasswordCredentialEnrollmentService;
import io.wyrmgate.iam.identity.application.IdentityAuthenticationQuery;
import io.wyrmgate.iam.identity.application.IdentityCommandService;
import io.wyrmgate.iam.identity.application.PrincipalCommandService;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import io.wyrmgate.iam.platform.tenant.TenantProvisioningService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InstallationBootstrapServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-13T00:00:00Z");

    @Test
    void runsFreshInstallationThroughVerifiedStateAndIrreversibleSeal() {
        UUID registrationId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID principalId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        MutableRepository repository = new MutableRepository();
        TenantProvisioningService tenants = mock(TenantProvisioningService.class);
        IdentityCommandService identities = mock(IdentityCommandService.class);
        CatalogCommandService catalog = mock(CatalogCommandService.class);
        SsoClientRegistrationService ssoClients = mock(SsoClientRegistrationService.class);
        PrincipalCommandService principals = mock(PrincipalCommandService.class);
        PasswordCredentialEnrollmentService passwords = mock(PasswordCredentialEnrollmentService.class);
        InitialAdminBootstrapService administration = mock(InitialAdminBootstrapService.class);
        IdentityAuthenticationQuery authenticationSubjects = mock(IdentityAuthenticationQuery.class);
        CredentialAuthenticationService credentialAuthentication = mock(CredentialAuthenticationService.class);
        SsoClientProtocolQuery protocolClients = mock(SsoClientProtocolQuery.class);
        IdGenerator ids = mock(IdGenerator.class);

        when(ids.nextId()).thenReturn(
                registrationId,
                tenantId,
                identityId,
                applicationId,
                targetId,
                principalId,
                credentialId,
                correlationId);
        when(tenants.matches(tenantId, "Wyrmgate")).thenReturn(true);
        when(authenticationSubjects.resolveEligible(
                        new TenantContext(tenantId), targetId, "admin"))
                .thenReturn(Optional.of(new IdentityAuthenticationQuery.AuthenticationSubject(
                        principalId, identityId, 1, 1)));
        when(credentialAuthentication.verifyPassword(
                        eq(new TenantContext(tenantId)), eq(principalId), any(char[].class), eq(NOW)))
                .thenReturn(Optional.of(new CredentialAuthenticationService.VerifiedCredential(
                        principalId, credentialId, 1)));

        SsoClientRegistration registration = mock(SsoClientRegistration.class);
        when(registration.id()).thenReturn(registrationId);
        when(registration.applicationId()).thenReturn(applicationId);
        when(protocolClients.resolveActive("wc_" + registrationId.toString().replace("-", "")))
                .thenReturn(Optional.of(new SsoClientProtocolQuery.ResolvedClient(
                        new TenantContext(tenantId), registration)));
        when(administration.isEffectiveFirstPartyBootstrap(
                        new TenantContext(tenantId), identityId, principalId, NOW))
                .thenReturn(true);

        InstallationBootstrapService service = new InstallationBootstrapService(
                repository,
                tenants,
                identities,
                catalog,
                ssoClients,
                principals,
                passwords,
                administration,
                authenticationSubjects,
                credentialAuthentication,
                protocolClients,
                ids);

        char[] password = "correct horse battery staple".toCharArray();
        InstallationBootstrapService.Result result = service.run(
                new InstallationBootstrapService.Input(
                        "Wyrmgate",
                        "Initial Administrator",
                        "admin",
                        "https://wyrmgate.example/callback"),
                password,
                NOW);

        assertEquals(InstallationBootstrapState.SEALED, result.state());
        assertEquals(tenantId, result.tenantId());
        assertEquals(identityId, result.identityId());
        assertEquals(principalId, result.principalId());
        assertEquals(credentialId, result.credentialId());
        assertEquals(NOW, result.sealedAt());
        assertEquals(InstallationBootstrapState.SEALED, repository.find().orElseThrow().state());

        verify(tenants).ensureFirstTenant(tenantId, "Wyrmgate", NOW);
        verify(identities).ensureInstallationPerson(
                eq(new TenantContext(tenantId)), eq(identityId), eq("Initial Administrator"),
                eq(NOW), eq(correlationId), eq(correlationId));
        verify(principals).ensureInstallationPrincipal(
                eq(new TenantContext(tenantId)), eq(principalId), eq(targetId), eq("admin"),
                eq(identityId), eq(NOW), eq(correlationId), eq(correlationId));
        verify(passwords).ensureInstallationPassword(
                eq(new TenantContext(tenantId)), eq(credentialId), eq(principalId),
                any(char[].class), eq(NOW));
        verify(administration).bootstrapFirstParty(
                new TenantContext(tenantId), identityId, principalId, NOW, correlationId);
    }

    @Test
    void sealedBootstrapDoesNotRequirePasswordOrInvokeCapabilityMutations() {
        UUID tenantId = UUID.randomUUID();
        InstallationBootstrapPlan sealed = new InstallationBootstrapPlan(
                InstallationBootstrapState.SEALED,
                tenantId,
                "Wyrmgate",
                UUID.randomUUID(),
                "Initial Administrator",
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "wc_client",
                "https://wyrmgate.example/callback",
                UUID.randomUUID(),
                "admin",
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                NOW,
                NOW,
                NOW);

        MutableRepository repository = new MutableRepository();
        repository.plan = sealed;
        TenantProvisioningService tenants = mock(TenantProvisioningService.class);
        IdentityCommandService identities = mock(IdentityCommandService.class);
        CatalogCommandService catalog = mock(CatalogCommandService.class);
        SsoClientRegistrationService ssoClients = mock(SsoClientRegistrationService.class);
        PrincipalCommandService principals = mock(PrincipalCommandService.class);
        PasswordCredentialEnrollmentService passwords = mock(PasswordCredentialEnrollmentService.class);
        InitialAdminBootstrapService administration = mock(InitialAdminBootstrapService.class);

        InstallationBootstrapService service = new InstallationBootstrapService(
                repository,
                tenants,
                identities,
                catalog,
                ssoClients,
                principals,
                passwords,
                administration,
                mock(IdentityAuthenticationQuery.class),
                mock(CredentialAuthenticationService.class),
                mock(SsoClientProtocolQuery.class),
                mock(IdGenerator.class));

        InstallationBootstrapService.Result result = service.run(
                new InstallationBootstrapService.Input(
                        "Wyrmgate",
                        "Initial Administrator",
                        "admin",
                        "https://wyrmgate.example/callback"),
                null,
                NOW.plusSeconds(60));

        assertEquals(InstallationBootstrapState.SEALED, result.state());
        assertEquals(NOW, result.sealedAt());
        verify(tenants, never()).ensureFirstTenant(any(), any(), any());
        verify(identities, never()).ensureInstallationPerson(any(), any(), any(), any(), any(), any());
        verify(principals, never()).ensureInstallationPrincipal(
                any(), any(), any(), any(), any(), any(), any(), any());
        verify(passwords, never()).ensureInstallationPassword(any(), any(), any(), any(), any());
        assertNull(repository.lastAdvance);
    }

    private static final class MutableRepository implements InstallationBootstrapRepository {
        private InstallationBootstrapPlan plan;
        private InstallationBootstrapState lastAdvance;

        @Override
        public Optional<InstallationBootstrapPlan> find() {
            return Optional.ofNullable(plan);
        }

        @Override
        public boolean createPlan(InstallationBootstrapPlan proposed) {
            if (plan != null) return false;
            plan = proposed;
            return true;
        }

        @Override
        public boolean advance(
                InstallationBootstrapState expected,
                InstallationBootstrapState next,
                Instant now) {
            if (plan == null || plan.state() != expected) return false;
            plan = plan.withState(next, now);
            lastAdvance = next;
            return true;
        }
    }
}
