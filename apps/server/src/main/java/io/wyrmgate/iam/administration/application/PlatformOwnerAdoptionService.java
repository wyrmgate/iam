package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.administration.domain.AdministrativeGrant;
import io.wyrmgate.iam.administration.domain.AdministrativeGrantState;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.administration.domain.AdministrativeRole;
import io.wyrmgate.iam.administration.domain.AdministrativeScopeType;
import io.wyrmgate.iam.administration.domain.InitialAdminAuthenticationAnchorType;
import io.wyrmgate.iam.administration.domain.InitialAdminBootstrap;
import io.wyrmgate.iam.administration.domain.OwnerAuthorityState;
import io.wyrmgate.iam.administration.domain.PlatformOwnerAuthority;
import io.wyrmgate.iam.administration.domain.TenantOwnerAuthority;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * ADR-0049 explicit one-shot adoption for a sealed pre-ADR-0049 first-party installation.
 * The operation proves the exact historical bootstrap lineage and never infers authority from
 * names, role codes or a tenant GLOBAL scope alone.
 */
public final class PlatformOwnerAdoptionService {

    private final InitialAdminBootstrapRepository bootstrapRepository;
    private final AdministrativeAuthorityRepository authorityRepository;
    private final StandaloneTenantOwnerRecoveryRepository historicalEvidenceRepository;
    private final PlatformOwnerAdoptionRepository adoptionRepository;
    private final GovernedActorStatusQuery governedActors;
    private final FirstPartyInitialAdminAnchorQuery firstPartyAnchors;
    private final PlatformOwnerAdoptionFactSink facts;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;

    public PlatformOwnerAdoptionService(
            InitialAdminBootstrapRepository bootstrapRepository,
            AdministrativeAuthorityRepository authorityRepository,
            StandaloneTenantOwnerRecoveryRepository historicalEvidenceRepository,
            PlatformOwnerAdoptionRepository adoptionRepository,
            GovernedActorStatusQuery governedActors,
            FirstPartyInitialAdminAnchorQuery firstPartyAnchors,
            PlatformOwnerAdoptionFactSink facts,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this.bootstrapRepository = Objects.requireNonNull(bootstrapRepository, "bootstrapRepository");
        this.authorityRepository = Objects.requireNonNull(authorityRepository, "authorityRepository");
        this.historicalEvidenceRepository = Objects.requireNonNull(historicalEvidenceRepository, "historicalEvidenceRepository");
        this.adoptionRepository = Objects.requireNonNull(adoptionRepository, "adoptionRepository");
        this.governedActors = Objects.requireNonNull(governedActors, "governedActors");
        this.firstPartyAnchors = Objects.requireNonNull(firstPartyAnchors, "firstPartyAnchors");
        this.facts = Objects.requireNonNull(facts, "facts");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public PlatformOwnerAdoptionResult adopt(
            TenantContext tenant,
            UUID expectedIdentityId,
            UUID expectedPrincipalId,
            UUID expectedBootstrapCorrelationId,
            Instant now,
            UUID correlationId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(expectedIdentityId, "expectedIdentityId");
        Objects.requireNonNull(expectedPrincipalId, "expectedPrincipalId");
        Objects.requireNonNull(expectedBootstrapCorrelationId, "expectedBootstrapCorrelationId");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(correlationId, "correlationId");
        return transactions.required(() -> adoptInTransaction(
                tenant,
                expectedIdentityId,
                expectedPrincipalId,
                expectedBootstrapCorrelationId,
                now,
                correlationId));
    }

    private PlatformOwnerAdoptionResult adoptInTransaction(
            TenantContext tenant,
            UUID expectedIdentityId,
            UUID expectedPrincipalId,
            UUID expectedBootstrapCorrelationId,
            Instant now,
            UUID correlationId) {
        InitialAdminBootstrap marker = bootstrapRepository.findBootstrap(tenant)
                .orElseThrow(() -> notEligible("bootstrap_marker_missing"));
        require(marker.authenticationAnchorType() == InitialAdminAuthenticationAnchorType.FIRST_PARTY_PRINCIPAL,
                "authentication_anchor_not_first_party_principal");
        require(marker.actorBindingId() == null, "first_party_marker_has_external_binding");
        require(expectedIdentityId.equals(marker.actorIdentityId()), "bootstrap_identity_mismatch");
        require(expectedPrincipalId.equals(marker.actorPrincipalId()), "bootstrap_principal_mismatch");
        require(expectedBootstrapCorrelationId.equals(marker.correlationId()), "bootstrap_correlation_mismatch");
        require(governedActors.isAdministrativelyEligible(tenant, expectedIdentityId),
                "bootstrap_identity_not_administratively_eligible");
        require(firstPartyAnchors.isEligiblePrincipalForIdentity(
                        tenant, expectedPrincipalId, expectedIdentityId),
                "bootstrap_principal_not_authentication_eligible_for_identity");

        AdministrativeRole role = authorityRepository.findRole(tenant, marker.administrativeRoleId())
                .orElseThrow(() -> notEligible("bootstrap_role_missing"));
        AdministrativeGrant grant = authorityRepository.findGrant(tenant, marker.administrativeGrantId())
                .orElseThrow(() -> notEligible("bootstrap_grant_missing"));
        validateRole(role, marker);
        validateGrant(grant, marker);
        validateExclusiveHistoricalAuthority(tenant, marker);

        long platformOwners = adoptionRepository.countPlatformOwnerAuthorities();
        long matchingPlatformOwners = adoptionRepository.countMatchingActivePlatformOwner(
                tenant, expectedIdentityId, expectedPrincipalId);
        long tenantOwners = adoptionRepository.countTenantOwnerAuthorities(tenant);
        long matchingTenantOwners = adoptionRepository.countMatchingActiveTenantOwner(
                tenant, expectedIdentityId, marker.administrativeGrantId());

        if (platformOwners > 0 || tenantOwners > 0) {
            require(platformOwners == 1 && matchingPlatformOwners == 1,
                    "deployment_platform_owner_state_not_exact_adopted_lineage");
            require(tenantOwners == 1 && matchingTenantOwners == 1,
                    "tenant_owner_state_not_exact_adopted_lineage");
            return new PlatformOwnerAdoptionResult(
                    PlatformOwnerAdoptionResult.Status.ALREADY_COMPLETE,
                    expectedIdentityId,
                    null,
                    null);
        }

        UUID tenantOwnerId = ids.nextId();
        UUID platformOwnerId = ids.nextId();
        bootstrapRepository.insertTenantOwnerAuthority(
                tenant,
                new TenantOwnerAuthority(
                        tenantOwnerId,
                        expectedIdentityId,
                        marker.administrativeGrantId(),
                        OwnerAuthorityState.ACTIVE,
                        null,
                        1,
                        now,
                        now));
        bootstrapRepository.insertPlatformOwnerAuthority(
                new PlatformOwnerAuthority(
                        platformOwnerId,
                        tenant.tenantId(),
                        expectedIdentityId,
                        expectedPrincipalId,
                        OwnerAuthorityState.ACTIVE,
                        true,
                        true,
                        null,
                        now,
                        null,
                        null,
                        null,
                        correlationId,
                        expectedBootstrapCorrelationId,
                        1,
                        now,
                        now));
        facts.adopted(
                tenant,
                expectedIdentityId,
                expectedPrincipalId,
                tenantOwnerId,
                platformOwnerId,
                now,
                correlationId);
        return new PlatformOwnerAdoptionResult(
                PlatformOwnerAdoptionResult.Status.ADOPTED,
                expectedIdentityId,
                tenantOwnerId,
                platformOwnerId);
    }

    private static void validateRole(AdministrativeRole role, InitialAdminBootstrap marker) {
        require(role.id().equals(marker.administrativeRoleId()), "bootstrap_role_id_mismatch");
        require(role.code().equals(InitialAdminBootstrapService.INITIAL_ROLE_CODE), "bootstrap_role_code_mismatch");
        require(role.name().equals(InitialAdminBootstrapService.INITIAL_ROLE_NAME), "bootstrap_role_name_mismatch");
        require(role.createdAt().equals(marker.completedAt()), "bootstrap_role_created_at_mismatch");
        require(role.permissions().equals(AdministrativePermissions.STANDALONE_TENANT_OWNER),
                "bootstrap_role_not_exact_tenant_owner_profile");
        require(role.revision() == 1 || role.revision() == 2, "bootstrap_role_revision_ambiguous");
        if (role.revision() == 1) {
            require(role.updatedAt().equals(marker.completedAt()), "bootstrap_role_timestamp_mismatch");
        } else {
            require(!role.updatedAt().isBefore(marker.completedAt()), "bootstrap_role_timestamp_invalid");
        }
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

    private void validateExclusiveHistoricalAuthority(TenantContext tenant, InitialAdminBootstrap marker) {
        require(historicalEvidenceRepository.countGrantsForActor(tenant, marker.actorIdentityId()) == 1,
                "bootstrap_identity_has_additional_grants");
        require(historicalEvidenceRepository.countGrantsForRole(tenant, marker.administrativeRoleId()) == 1,
                "bootstrap_role_has_additional_grants");
        require(historicalEvidenceRepository.countDelegationsForRole(tenant, marker.administrativeRoleId()) == 0,
                "bootstrap_role_has_delegations");
        require(historicalEvidenceRepository.countElevationsForRole(tenant, marker.administrativeRoleId()) == 0,
                "bootstrap_role_has_elevations");
        require(historicalEvidenceRepository.countBreakGlassOperationsForRole(tenant, marker.administrativeRoleId()) == 0,
                "bootstrap_role_has_break_glass_operations");
    }

    private static void require(boolean condition, String reason) {
        if (!condition) {
            throw notEligible(reason);
        }
    }

    private static PlatformOwnerAdoptionNotEligibleException notEligible(String reason) {
        return new PlatformOwnerAdoptionNotEligibleException(reason);
    }
}
