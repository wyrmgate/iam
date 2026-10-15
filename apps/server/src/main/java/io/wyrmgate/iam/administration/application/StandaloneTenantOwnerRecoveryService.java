package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.administration.domain.AdministrativeGrant;
import io.wyrmgate.iam.administration.domain.AdministrativeGrantState;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.administration.domain.AdministrativeRole;
import io.wyrmgate.iam.administration.domain.AdministrativeScopeType;
import io.wyrmgate.iam.administration.domain.InitialAdminAuthenticationAnchorType;
import io.wyrmgate.iam.administration.domain.InitialAdminBootstrap;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * ADR-0047 one-shot compatibility recovery for an already-sealed standalone first-party install.
 * This is deliberately separate from ordinary Administrative Authorization mutation services.
 */
public final class StandaloneTenantOwnerRecoveryService {

    private final InitialAdminBootstrapRepository bootstrapRepository;
    private final AdministrativeAuthorityRepository authorityRepository;
    private final StandaloneTenantOwnerRecoveryRepository recoveryRepository;
    private final GovernedActorStatusQuery governedActorStatusQuery;
    private final FirstPartyInitialAdminAnchorQuery firstPartyAnchorQuery;
    private final TransactionExecutor transactionExecutor;

    public StandaloneTenantOwnerRecoveryService(
            InitialAdminBootstrapRepository bootstrapRepository,
            AdministrativeAuthorityRepository authorityRepository,
            StandaloneTenantOwnerRecoveryRepository recoveryRepository,
            GovernedActorStatusQuery governedActorStatusQuery,
            FirstPartyInitialAdminAnchorQuery firstPartyAnchorQuery,
            TransactionExecutor transactionExecutor) {
        this.bootstrapRepository = Objects.requireNonNull(bootstrapRepository, "bootstrapRepository");
        this.authorityRepository = Objects.requireNonNull(authorityRepository, "authorityRepository");
        this.recoveryRepository = Objects.requireNonNull(recoveryRepository, "recoveryRepository");
        this.governedActorStatusQuery = Objects.requireNonNull(governedActorStatusQuery, "governedActorStatusQuery");
        this.firstPartyAnchorQuery = Objects.requireNonNull(firstPartyAnchorQuery, "firstPartyAnchorQuery");
        this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
    }

    public StandaloneTenantOwnerRecoveryResult recover(
            TenantContext tenant,
            UUID expectedIdentityId,
            UUID expectedPrincipalId,
            UUID expectedBootstrapCorrelationId,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(expectedIdentityId, "expectedIdentityId");
        Objects.requireNonNull(expectedPrincipalId, "expectedPrincipalId");
        Objects.requireNonNull(expectedBootstrapCorrelationId, "expectedBootstrapCorrelationId");
        Objects.requireNonNull(now, "now");
        return transactionExecutor.required(() -> recoverInTransaction(
                tenant,
                expectedIdentityId,
                expectedPrincipalId,
                expectedBootstrapCorrelationId,
                now));
    }

    private StandaloneTenantOwnerRecoveryResult recoverInTransaction(
            TenantContext tenant,
            UUID expectedIdentityId,
            UUID expectedPrincipalId,
            UUID expectedBootstrapCorrelationId,
            Instant now) {
        InitialAdminBootstrap marker = bootstrapRepository.findBootstrap(tenant)
                .orElseThrow(() -> notEligible("bootstrap_marker_missing"));
        require(marker.authenticationAnchorType() == InitialAdminAuthenticationAnchorType.FIRST_PARTY_PRINCIPAL,
                "authentication_anchor_not_first_party_principal");
        require(marker.actorBindingId() == null, "first_party_marker_has_external_binding");
        require(expectedIdentityId.equals(marker.actorIdentityId()), "bootstrap_identity_mismatch");
        require(expectedPrincipalId.equals(marker.actorPrincipalId()), "bootstrap_principal_mismatch");
        require(expectedBootstrapCorrelationId.equals(marker.correlationId()), "bootstrap_correlation_mismatch");
        require(governedActorStatusQuery.isAdministrativelyEligible(tenant, expectedIdentityId),
                "bootstrap_identity_not_administratively_eligible");
        require(firstPartyAnchorQuery.isEligiblePrincipalForIdentity(
                        tenant, expectedPrincipalId, expectedIdentityId),
                "bootstrap_principal_not_authentication_eligible_for_identity");

        AdministrativeRole role = authorityRepository.findRole(tenant, marker.administrativeRoleId())
                .orElseThrow(() -> notEligible("bootstrap_role_missing"));
        AdministrativeGrant grant = authorityRepository.findGrant(tenant, marker.administrativeGrantId())
                .orElseThrow(() -> notEligible("bootstrap_grant_missing"));

        validateRoleIdentity(role, marker);
        validateGrant(grant, marker);
        validateExclusiveBootstrapAuthority(tenant, marker);

        if (role.permissions().equals(AdministrativePermissions.STANDALONE_TENANT_OWNER)) {
            require(role.revision() == 1 || role.revision() == 2, "owner_role_revision_ambiguous");
            if (role.revision() == 1) {
                require(role.updatedAt().equals(marker.completedAt()), "fresh_owner_role_timestamp_mismatch");
            } else {
                require(!role.updatedAt().isBefore(marker.completedAt()), "owner_role_timestamp_invalid");
            }
            return new StandaloneTenantOwnerRecoveryResult(
                    StandaloneTenantOwnerRecoveryResult.Status.ALREADY_COMPLETE,
                    role.id());
        }

        require(role.permissions().equals(AdministrativePermissions.INITIAL_TENANT_ADMIN),
                "bootstrap_role_permissions_not_exact_legacy_profile");
        require(role.revision() == 1, "legacy_role_revision_not_initial");
        require(role.updatedAt().equals(marker.completedAt()), "legacy_role_timestamp_mismatch");

        recoveryRepository.upgradeLegacyRolePermissions(
                tenant,
                role.id(),
                role.revision(),
                AdministrativePermissions.INITIAL_TENANT_ADMIN,
                AdministrativePermissions.STANDALONE_TENANT_OWNER,
                now);

        AdministrativeRole recovered = authorityRepository.findRole(tenant, role.id())
                .orElseThrow(() -> notEligible("bootstrap_role_missing_after_recovery"));
        require(recovered.revision() == 2, "recovered_role_revision_unexpected");
        require(recovered.permissions().equals(AdministrativePermissions.STANDALONE_TENANT_OWNER),
                "recovered_role_permissions_not_exact_owner_profile");
        return new StandaloneTenantOwnerRecoveryResult(
                StandaloneTenantOwnerRecoveryResult.Status.RECOVERED,
                recovered.id());
    }

    private static void validateRoleIdentity(AdministrativeRole role, InitialAdminBootstrap marker) {
        require(role.id().equals(marker.administrativeRoleId()), "bootstrap_role_id_mismatch");
        require(role.code().equals(InitialAdminBootstrapService.INITIAL_ROLE_CODE), "bootstrap_role_code_mismatch");
        require(role.name().equals(InitialAdminBootstrapService.INITIAL_ROLE_NAME), "bootstrap_role_name_mismatch");
        require(role.createdAt().equals(marker.completedAt()), "bootstrap_role_created_at_mismatch");
    }

    private static void validateGrant(AdministrativeGrant grant, InitialAdminBootstrap marker) {
        require(grant.id().equals(marker.administrativeGrantId()), "bootstrap_grant_id_mismatch");
        require(grant.actorIdentityId().equals(marker.actorIdentityId()), "bootstrap_grant_identity_mismatch");
        require(grant.roleId().equals(marker.administrativeRoleId()), "bootstrap_grant_role_mismatch");
        require(grant.scope().type() == AdministrativeScopeType.GLOBAL, "bootstrap_grant_scope_not_global");
        require(grant.state() == AdministrativeGrantState.ACTIVE, "bootstrap_grant_not_active");
        require(grant.validFrom() != null && grant.validFrom().equals(marker.completedAt()),
                "bootstrap_grant_valid_from_mismatch");
        require(grant.validUntil() == null, "bootstrap_grant_has_valid_until");
        require(grant.grantable(), "bootstrap_grant_not_grantable");
        require(grant.delegable(), "bootstrap_grant_not_delegable");
        require(grant.authorityBasisGrantId() == null, "bootstrap_grant_has_authority_basis");
        require(grant.revision() == 1, "bootstrap_grant_revision_not_initial");
        require(grant.createdAt().equals(marker.completedAt()), "bootstrap_grant_created_at_mismatch");
        require(grant.updatedAt().equals(marker.completedAt()), "bootstrap_grant_updated_at_mismatch");
    }

    private void validateExclusiveBootstrapAuthority(TenantContext tenant, InitialAdminBootstrap marker) {
        require(recoveryRepository.countGrantsForActor(tenant, marker.actorIdentityId()) == 1,
                "bootstrap_identity_has_additional_grants");
        require(recoveryRepository.countGrantsForRole(tenant, marker.administrativeRoleId()) == 1,
                "bootstrap_role_has_additional_grants");
        require(recoveryRepository.countDelegationsForRole(tenant, marker.administrativeRoleId()) == 0,
                "bootstrap_role_has_delegations");
    }

    private static void require(boolean condition, String reason) {
        if (!condition) {
            throw notEligible(reason);
        }
    }

    private static StandaloneTenantOwnerRecoveryNotEligibleException notEligible(String reason) {
        return new StandaloneTenantOwnerRecoveryNotEligibleException(reason);
    }
}
