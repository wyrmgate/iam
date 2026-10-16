package io.wyrmgate.iam.administration.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wyrmgate.iam.administration.domain.AdministrativeGrant;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.administration.domain.AdministrativeRole;
import io.wyrmgate.iam.administration.domain.ControlPlaneActorBinding;
import io.wyrmgate.iam.administration.domain.ExternalAuthenticationSubject;
import io.wyrmgate.iam.administration.domain.InitialAdminBootstrap;
import io.wyrmgate.iam.administration.domain.PlatformOwnerAuthority;
import io.wyrmgate.iam.administration.domain.TenantOwnerAuthority;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class InitialAdminBootstrapServiceTest {

    @Test
    void firstPartyBootstrapFailsClosedWhenPrincipalDoesNotBelongToEligibleIdentity() {
        TenantContext tenant = new TenantContext(UUID.randomUUID());
        UUID identityId = UUID.randomUUID();
        UUID principalId = UUID.randomUUID();

        InitialAdminBootstrapService service = new InitialAdminBootstrapService(
                new NoMutationBootstrapRepository(),
                new NoMutationActorBindingRepository(),
                (candidateTenant, candidateIdentityId) -> true,
                (candidateTenant, candidatePrincipalId, candidateIdentityId) -> false,
                (candidateTenant, bootstrap) -> {
                    throw new AssertionError("fact sink must not be reached");
                },
                UUID::randomUUID,
                new ImmediateTransactionExecutor());

        InitialAdminBootstrapNotAllowedException exception = assertThrows(
                InitialAdminBootstrapNotAllowedException.class,
                () -> service.bootstrapFirstParty(
                        tenant,
                        identityId,
                        principalId,
                        Instant.parse("2026-10-13T00:00:00Z"),
                        UUID.randomUUID()));

        assertEquals(
                "initial administrative bootstrap is not allowed: actor_principal_not_authentication_eligible_for_identity",
                exception.getMessage());
    }

    @Test
    void firstPartyBootstrapCreatesTenantAndPlatformOwnerAuthority() {
        RecordingBootstrapRepository repository = new RecordingBootstrapRepository();
        InitialAdminBootstrapService service = new InitialAdminBootstrapService(
                repository,
                new RecordingActorBindingRepository(),
                (tenant, identityId) -> true,
                (tenant, principalId, identityId) -> true,
                (tenant, bootstrap) -> {},
                UUID::randomUUID,
                new ImmediateTransactionExecutor());

        TenantContext tenant = new TenantContext(UUID.randomUUID());
        UUID identityId = UUID.randomUUID();
        UUID principalId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-15T00:00:00Z");
        service.bootstrapFirstParty(tenant, identityId, principalId, now, UUID.randomUUID());

        assertEquals(AdministrativePermissions.STANDALONE_TENANT_OWNER, repository.role.permissions());
        assertTrue(repository.role.permissions().contains(AdministrativePermissions.APPLICATION_CREATE));
        assertTrue(repository.role.permissions().contains(AdministrativePermissions.CONNECTOR_CREATE));
        assertTrue(repository.role.permissions().contains(AdministrativePermissions.AUDIT_READ));
        assertTrue(repository.grant.grantable());
        assertTrue(repository.grant.delegable());
        assertNotNull(repository.tenantOwner);
        assertEquals(identityId, repository.tenantOwner.actorIdentityId());
        assertEquals(repository.grant.id(), repository.tenantOwner.administrativeGrantId());
        assertNotNull(repository.platformOwner);
        assertEquals(tenant.tenantId(), repository.platformOwner.actorTenantId());
        assertEquals(identityId, repository.platformOwner.actorIdentityId());
        assertEquals(principalId, repository.platformOwner.actorPrincipalId());
        assertTrue(repository.platformOwner.grantable());
        assertTrue(repository.platformOwner.delegable());
        assertTrue(service.isEffectiveFirstPartyBootstrap(tenant, identityId, principalId, now));
    }

    @Test
    void externalBootstrapRetainsNarrowAdr0011AuthorityAndCreatesNoOwnerDesignation() {
        RecordingBootstrapRepository repository = new RecordingBootstrapRepository();
        InitialAdminBootstrapService service = new InitialAdminBootstrapService(
                repository,
                new RecordingActorBindingRepository(),
                (tenant, identityId) -> true,
                (tenant, principalId, identityId) -> false,
                (tenant, bootstrap) -> {},
                UUID::randomUUID,
                new ImmediateTransactionExecutor());

        service.bootstrapExternal(
                new TenantContext(UUID.randomUUID()),
                UUID.randomUUID(),
                new ExternalAuthenticationSubject("https://issuer.example", "first-admin"),
                Instant.parse("2026-10-15T00:00:00Z"),
                UUID.randomUUID());

        assertEquals(AdministrativePermissions.INITIAL_TENANT_ADMIN, repository.role.permissions());
        assertEquals(4, repository.role.permissions().size());
        assertNull(repository.tenantOwner);
        assertNull(repository.platformOwner);
    }

    private static final class RecordingBootstrapRepository implements InitialAdminBootstrapRepository {
        private InitialAdminBootstrap bootstrap;
        private AdministrativeRole role;
        private AdministrativeGrant grant;
        private TenantOwnerAuthority tenantOwner;
        private PlatformOwnerAuthority platformOwner;

        @Override
        public Optional<InitialAdminBootstrap> findBootstrap(TenantContext tenant) {
            return Optional.ofNullable(bootstrap);
        }

        @Override
        public boolean hasAnyAdministrativeGrant(TenantContext tenant) {
            return false;
        }

        @Override
        public boolean isGrantEffective(
                TenantContext tenant,
                UUID grantId,
                UUID actorIdentityId,
                Instant at) {
            return grant != null && grant.id().equals(grantId) && grant.actorIdentityId().equals(actorIdentityId);
        }

        @Override
        public boolean isTenantOwnerAuthorityEffective(
                TenantContext tenant,
                UUID grantId,
                UUID actorIdentityId,
                Instant at) {
            return tenantOwner != null
                    && tenantOwner.administrativeGrantId().equals(grantId)
                    && tenantOwner.actorIdentityId().equals(actorIdentityId);
        }

        @Override
        public boolean isPlatformOwnerAuthorityEffective(
                TenantContext actorTenant,
                UUID actorIdentityId,
                UUID actorPrincipalId,
                Instant at) {
            return platformOwner != null
                    && platformOwner.actorTenantId().equals(actorTenant.tenantId())
                    && platformOwner.actorIdentityId().equals(actorIdentityId)
                    && platformOwner.actorPrincipalId().equals(actorPrincipalId)
                    && platformOwner.isEffectiveAt(at);
        }

        @Override
        public boolean claimBootstrap(TenantContext tenant, InitialAdminBootstrap candidate) {
            if (bootstrap != null) {
                return false;
            }
            bootstrap = candidate;
            return true;
        }

        @Override
        public UUID ensurePermission(
                TenantContext tenant,
                UUID proposedPermissionId,
                AdministrativePermission permission,
                Instant now) {
            return proposedPermissionId;
        }

        @Override
        public void insertRole(TenantContext tenant, AdministrativeRole candidate) {
            role = candidate;
        }

        @Override
        public void attachPermissions(
                TenantContext tenant,
                UUID roleId,
                Collection<UUID> permissionIds,
                Instant now) {
            assertEquals(role.id(), roleId);
            assertEquals(role.permissions().size(), permissionIds.size());
        }

        @Override
        public void insertGrant(TenantContext tenant, AdministrativeGrant candidate) {
            grant = candidate;
        }

        @Override
        public void insertTenantOwnerAuthority(TenantContext tenant, TenantOwnerAuthority candidate) {
            tenantOwner = candidate;
        }

        @Override
        public void insertPlatformOwnerAuthority(PlatformOwnerAuthority candidate) {
            platformOwner = candidate;
        }
    }

    private static final class RecordingActorBindingRepository implements ControlPlaneActorBindingRepository {
        @Override
        public Optional<ControlPlaneActorBinding> findActiveByExternalSubject(
                ExternalAuthenticationSubject externalSubject) {
            return Optional.empty();
        }

        @Override
        public boolean insertIfExternalSubjectUnbound(
                TenantContext tenant,
                ControlPlaneActorBinding binding) {
            return true;
        }
    }

    private static final class NoMutationBootstrapRepository implements InitialAdminBootstrapRepository {
        @Override
        public Optional<InitialAdminBootstrap> findBootstrap(TenantContext tenant) {
            return Optional.empty();
        }

        @Override
        public boolean hasAnyAdministrativeGrant(TenantContext tenant) {
            return false;
        }

        @Override
        public boolean isGrantEffective(
                TenantContext tenant,
                UUID grantId,
                UUID actorIdentityId,
                Instant at) {
            return false;
        }

        @Override
        public boolean isTenantOwnerAuthorityEffective(
                TenantContext tenant,
                UUID grantId,
                UUID actorIdentityId,
                Instant at) {
            return false;
        }

        @Override
        public boolean isPlatformOwnerAuthorityEffective(
                TenantContext actorTenant,
                UUID actorIdentityId,
                UUID actorPrincipalId,
                Instant at) {
            return false;
        }

        @Override
        public boolean claimBootstrap(TenantContext tenant, InitialAdminBootstrap bootstrap) {
            throw new AssertionError("bootstrap marker must not be claimed");
        }

        @Override
        public UUID ensurePermission(
                TenantContext tenant,
                UUID proposedPermissionId,
                AdministrativePermission permission,
                Instant now) {
            throw new AssertionError("permissions must not be created");
        }

        @Override
        public void insertRole(TenantContext tenant, AdministrativeRole role) {
            throw new AssertionError("role must not be created");
        }

        @Override
        public void attachPermissions(
                TenantContext tenant,
                UUID roleId,
                Collection<UUID> permissionIds,
                Instant now) {
            throw new AssertionError("permissions must not be attached");
        }

        @Override
        public void insertGrant(TenantContext tenant, AdministrativeGrant grant) {
            throw new AssertionError("grant must not be created");
        }

        @Override
        public void insertTenantOwnerAuthority(TenantContext tenant, TenantOwnerAuthority authority) {
            throw new AssertionError("tenant owner must not be created");
        }

        @Override
        public void insertPlatformOwnerAuthority(PlatformOwnerAuthority authority) {
            throw new AssertionError("platform owner must not be created");
        }
    }

    private static final class NoMutationActorBindingRepository implements ControlPlaneActorBindingRepository {
        @Override
        public Optional<ControlPlaneActorBinding> findActiveByExternalSubject(
                ExternalAuthenticationSubject externalSubject) {
            return Optional.empty();
        }

        @Override
        public boolean insertIfExternalSubjectUnbound(
                TenantContext tenant,
                ControlPlaneActorBinding binding) {
            throw new AssertionError("external binding must not be created");
        }
    }

    private static final class ImmediateTransactionExecutor implements TransactionExecutor {
        @Override
        public <T> T required(Supplier<T> work) {
            return work.get();
        }
    }
}
