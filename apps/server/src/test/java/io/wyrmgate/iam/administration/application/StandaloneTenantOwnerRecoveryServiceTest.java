package io.wyrmgate.iam.administration.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.wyrmgate.iam.administration.domain.AdministrativeGrant;
import io.wyrmgate.iam.administration.domain.AdministrativeGrantState;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.administration.domain.AdministrativeRole;
import io.wyrmgate.iam.administration.domain.AdministrativeScope;
import io.wyrmgate.iam.administration.domain.InitialAdminAuthenticationAnchorType;
import io.wyrmgate.iam.administration.domain.InitialAdminBootstrap;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StandaloneTenantOwnerRecoveryServiceTest {

    private static final Instant COMPLETED = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant RECOVERED = Instant.parse("2026-10-16T00:00:00Z");

    private final TenantContext tenant = new TenantContext(UUID.randomUUID());
    private final UUID identityId = UUID.randomUUID();
    private final UUID principalId = UUID.randomUUID();
    private final UUID roleId = UUID.randomUUID();
    private final UUID grantId = UUID.randomUUID();
    private final UUID correlationId = UUID.randomUUID();
    private InitialAdminBootstrapRepository bootstrapRepository;
    private AdministrativeAuthorityRepository authorityRepository;
    private StandaloneTenantOwnerRecoveryRepository recoveryRepository;
    private StandaloneTenantOwnerRecoveryService service;

    @BeforeEach
    void setUp() {
        bootstrapRepository = mock(InitialAdminBootstrapRepository.class);
        authorityRepository = mock(AdministrativeAuthorityRepository.class);
        recoveryRepository = mock(StandaloneTenantOwnerRecoveryRepository.class);
        when(bootstrapRepository.findBootstrap(tenant)).thenReturn(Optional.of(firstPartyMarker()));
        when(authorityRepository.findRole(tenant, roleId)).thenReturn(Optional.of(legacyRole()));
        when(authorityRepository.findGrant(tenant, grantId)).thenReturn(Optional.of(bootstrapGrant()));
        when(recoveryRepository.countGrantsForActor(tenant, identityId)).thenReturn(1L);
        when(recoveryRepository.countGrantsForRole(tenant, roleId)).thenReturn(1L);
        when(recoveryRepository.countDelegationsForRole(tenant, roleId)).thenReturn(0L);

        service = new StandaloneTenantOwnerRecoveryService(
                bootstrapRepository,
                authorityRepository,
                recoveryRepository,
                (candidateTenant, candidateIdentityId) -> true,
                (candidateTenant, candidatePrincipalId, candidateIdentityId) -> true,
                new ImmediateTransactionExecutor());
    }

    @Test
    void exactLegacyStateUpgradesOnceToFiniteStandaloneOwner() {
        AdministrativeRole recovered = ownerRole(2, RECOVERED);
        when(authorityRepository.findRole(tenant, roleId))
                .thenReturn(Optional.of(legacyRole()), Optional.of(recovered));

        StandaloneTenantOwnerRecoveryResult result = recover();

        assertEquals(StandaloneTenantOwnerRecoveryResult.Status.RECOVERED, result.status());
        verify(recoveryRepository).upgradeLegacyRolePermissions(
                tenant,
                roleId,
                1,
                AdministrativePermissions.INITIAL_TENANT_ADMIN,
                AdministrativePermissions.STANDALONE_TENANT_OWNER,
                RECOVERED);
    }

    @Test
    void alreadyRecoveredOwnerIsIdempotentNoOp() {
        when(authorityRepository.findRole(tenant, roleId)).thenReturn(Optional.of(ownerRole(2, RECOVERED)));

        StandaloneTenantOwnerRecoveryResult result = recover();

        assertEquals(StandaloneTenantOwnerRecoveryResult.Status.ALREADY_COMPLETE, result.status());
        verify(recoveryRepository, never()).upgradeLegacyRolePermissions(any(), any(), eq(1L), any(), any(), any());
    }

    @Test
    void freshAdr0046OwnerIsAlreadyComplete() {
        when(authorityRepository.findRole(tenant, roleId)).thenReturn(Optional.of(ownerRole(1, COMPLETED)));
        assertEquals(StandaloneTenantOwnerRecoveryResult.Status.ALREADY_COMPLETE, recover().status());
    }

    @Test
    void externalSubjectBootstrapIsRejected() {
        when(bootstrapRepository.findBootstrap(tenant)).thenReturn(Optional.of(new InitialAdminBootstrap(
                UUID.randomUUID(),
                identityId,
                InitialAdminAuthenticationAnchorType.EXTERNAL_SUBJECT,
                UUID.randomUUID(),
                null,
                roleId,
                grantId,
                COMPLETED,
                correlationId)));
        assertRejected("authentication_anchor_not_first_party_principal");
    }

    @Test
    void bootstrapCorrelationMismatchIsRejected() {
        assertThrows(
                StandaloneTenantOwnerRecoveryNotEligibleException.class,
                () -> service.recover(tenant, identityId, principalId, UUID.randomUUID(), RECOVERED));
        verify(recoveryRepository, never()).upgradeLegacyRolePermissions(any(), any(), eq(1L), any(), any(), any());
    }

    @Test
    void differentRoleCodeIsRejected() {
        when(authorityRepository.findRole(tenant, roleId)).thenReturn(Optional.of(new AdministrativeRole(
                roleId,
                "custom-admin",
                InitialAdminBootstrapService.INITIAL_ROLE_NAME,
                AdministrativePermissions.INITIAL_TENANT_ADMIN,
                1,
                COMPLETED,
                COMPLETED)));
        assertRejected("bootstrap_role_code_mismatch");
    }

    @Test
    void extraLegacyPermissionIsRejected() {
        Set<AdministrativePermission> permissions = new HashSet<>(AdministrativePermissions.INITIAL_TENANT_ADMIN);
        permissions.add(AdministrativePermissions.APPLICATION_READ);
        when(authorityRepository.findRole(tenant, roleId)).thenReturn(Optional.of(role(permissions, 1, COMPLETED)));
        assertRejected("bootstrap_role_permissions_not_exact_legacy_profile");
    }

    @Test
    void missingLegacyPermissionIsRejected() {
        Set<AdministrativePermission> permissions = new HashSet<>(AdministrativePermissions.INITIAL_TENANT_ADMIN);
        permissions.remove(AdministrativePermissions.IDENTITY_UPDATE);
        when(authorityRepository.findRole(tenant, roleId)).thenReturn(Optional.of(role(permissions, 1, COMPLETED)));
        assertRejected("bootstrap_role_permissions_not_exact_legacy_profile");
    }

    @Test
    void alteredGrantScopeIsRejected() {
        when(authorityRepository.findGrant(tenant, grantId)).thenReturn(Optional.of(new AdministrativeGrant(
                grantId,
                identityId,
                roleId,
                AdministrativeScope.specificResource("identity", UUID.randomUUID()),
                AdministrativeGrantState.ACTIVE,
                COMPLETED,
                null,
                true,
                true,
                null,
                1,
                COMPLETED,
                COMPLETED)));
        assertRejected("bootstrap_grant_scope_not_global");
    }

    @Test
    void alteredGrantabilityIsRejected() {
        when(authorityRepository.findGrant(tenant, grantId)).thenReturn(Optional.of(new AdministrativeGrant(
                grantId,
                identityId,
                roleId,
                AdministrativeScope.global(),
                AdministrativeGrantState.ACTIVE,
                COMPLETED,
                null,
                false,
                true,
                null,
                1,
                COMPLETED,
                COMPLETED)));
        assertRejected("bootstrap_grant_not_grantable");
    }

    @Test
    void alteredDelegabilityIsRejected() {
        when(authorityRepository.findGrant(tenant, grantId)).thenReturn(Optional.of(new AdministrativeGrant(
                grantId,
                identityId,
                roleId,
                AdministrativeScope.global(),
                AdministrativeGrantState.ACTIVE,
                COMPLETED,
                null,
                true,
                false,
                null,
                1,
                COMPLETED,
                COMPLETED)));
        assertRejected("bootstrap_grant_not_delegable");
    }

    @Test
    void additionalBootstrapIdentityGrantIsRejectedWithoutMutation() {
        when(recoveryRepository.countGrantsForActor(tenant, identityId)).thenReturn(2L);
        assertRejected("bootstrap_identity_has_additional_grants");
        verify(recoveryRepository, never()).upgradeLegacyRolePermissions(any(), any(), eq(1L), any(), any(), any());
    }

    @Test
    void delegationOfBootstrapRoleIsRejectedWithoutNormalization() {
        when(recoveryRepository.countDelegationsForRole(tenant, roleId)).thenReturn(1L);
        assertRejected("bootstrap_role_has_delegations");
    }

    @Test
    void ambiguousOwnerRevisionIsRejected() {
        when(authorityRepository.findRole(tenant, roleId)).thenReturn(Optional.of(ownerRole(3, RECOVERED)));
        assertRejected("owner_role_revision_ambiguous");
    }

    @Test
    void bootstrapIdentityMismatchIsRejectedBeforeAuthorityMutation() {
        assertThrows(
                StandaloneTenantOwnerRecoveryNotEligibleException.class,
                () -> service.recover(tenant, UUID.randomUUID(), principalId, correlationId, RECOVERED));
        verify(recoveryRepository, never()).upgradeLegacyRolePermissions(any(), any(), eq(1L), any(), any(), any());
    }

    private StandaloneTenantOwnerRecoveryResult recover() {
        return service.recover(tenant, identityId, principalId, correlationId, RECOVERED);
    }

    private void assertRejected(String reason) {
        StandaloneTenantOwnerRecoveryNotEligibleException exception = assertThrows(
                StandaloneTenantOwnerRecoveryNotEligibleException.class,
                this::recover);
        assertEquals(reason, exception.reason());
    }

    private InitialAdminBootstrap firstPartyMarker() {
        return new InitialAdminBootstrap(
                UUID.randomUUID(),
                identityId,
                InitialAdminAuthenticationAnchorType.FIRST_PARTY_PRINCIPAL,
                null,
                principalId,
                roleId,
                grantId,
                COMPLETED,
                correlationId);
    }

    private AdministrativeRole legacyRole() {
        return role(AdministrativePermissions.INITIAL_TENANT_ADMIN, 1, COMPLETED);
    }

    private AdministrativeRole ownerRole(long revision, Instant updatedAt) {
        return role(AdministrativePermissions.STANDALONE_TENANT_OWNER, revision, updatedAt);
    }

    private AdministrativeRole role(Set<AdministrativePermission> permissions, long revision, Instant updatedAt) {
        return new AdministrativeRole(
                roleId,
                InitialAdminBootstrapService.INITIAL_ROLE_CODE,
                InitialAdminBootstrapService.INITIAL_ROLE_NAME,
                permissions,
                revision,
                COMPLETED,
                updatedAt);
    }

    private AdministrativeGrant bootstrapGrant() {
        return new AdministrativeGrant(
                grantId,
                identityId,
                roleId,
                AdministrativeScope.global(),
                AdministrativeGrantState.ACTIVE,
                COMPLETED,
                null,
                true,
                true,
                null,
                1,
                COMPLETED,
                COMPLETED);
    }

    private static final class ImmediateTransactionExecutor implements TransactionExecutor {
        @Override
        public <T> T required(Supplier<T> work) {
            return work.get();
        }
    }
}
