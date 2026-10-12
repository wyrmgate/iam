package io.wyrmgate.iam.administration.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.wyrmgate.iam.administration.domain.AdministrativeGrant;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativeRole;
import io.wyrmgate.iam.administration.domain.ControlPlaneActorBinding;
import io.wyrmgate.iam.administration.domain.ExternalAuthenticationSubject;
import io.wyrmgate.iam.administration.domain.InitialAdminBootstrap;
import io.wyrmgate.iam.platform.id.IdGenerator;
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

    private static final class NoMutationBootstrapRepository implements InitialAdminBootstrapRepository {
        @Override
        public boolean hasAnyAdministrativeGrant(TenantContext tenant) {
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
