package io.wyrmgate.iam.integration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import io.wyrmgate.iam.access.application.DesiredProvisioningStateQuery;
import io.wyrmgate.iam.access.application.DesiredStateProjectionRepository.*;
import io.wyrmgate.iam.catalog.application.CatalogEntitlementReferenceQuery;
import io.wyrmgate.iam.catalog.application.CatalogTargetReferenceQuery;
import io.wyrmgate.iam.identity.application.PrincipalTechnicalReferenceQuery;
import io.wyrmgate.iam.integration.application.ConnectorWorkRepository.ProvisioningCandidate;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import io.wyrmgate.iam.tenancy.application.TenantOverviewQuery;
import io.wyrmgate.iam.tenancy.domain.TenantLifecycleState;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class CurrentProvisioningAdmissionTest {
    private final TenantContext tenant=new TenantContext(UUID.randomUUID());
    private final UUID identity=UUID.randomUUID(), principal=UUID.randomUUID(), target=UUID.randomUUID(), entitlement=UUID.randomUUID(),
        binding=UUID.randomUUID(), subject=UUID.randomUUID();
    private final ProvisioningPlanFence fence=mock(ProvisioningPlanFence.class);
    private final DesiredProvisioningStateQuery desired=mock(DesiredProvisioningStateQuery.class);
    private final GrantProvisioningRepository grants=mock(GrantProvisioningRepository.class);
    private final PrincipalTechnicalReferenceQuery identities=mock(PrincipalTechnicalReferenceQuery.class);
    private final CatalogEntitlementReferenceQuery catalog=mock(CatalogEntitlementReferenceQuery.class);
    private final TenantOverviewQuery tenants=mock(TenantOverviewQuery.class);
    private final CurrentProvisioningAdmission admission=new CurrentProvisioningAdmission(fence,desired,grants,
        mock(PrincipalProvisioningRepository.class),identities,catalog,mock(CatalogTargetReferenceQuery.class),tenants);
    private ProvisioningCandidate candidate(String op) {
        return new ProvisioningCandidate(UUID.randomUUID(),UUID.randomUUID(),tenant.tenantId(),binding,op,"DESIRED_GRANT",subject,2,
            "scim-2.group",1,"key",UUID.randomUUID(),null,Map.of("principalId",principal.toString(),"entitlementId",entitlement.toString(),
            "providerPrincipalId","p","providerEntitlementId","g"));
    }
    private void state(ProvisioningCandidate c,DesiredPresence presence) {
        Instant now=Instant.now();
        when(desired.desiredGrant(tenant,subject)).thenReturn(DesiredProvisioningStateQuery.Result.current(
            new DesiredGrantState(subject,identity,target,entitlement,"ANY",principal,presence,2,1,now)));
        when(fence.currentPlan(c,target)).thenReturn(true);
        when(fence.removalMappingSafe(c,principal,entitlement)).thenReturn(true);
    }
    @Test void increaseRequiresExactCurrentRouteAndActiveAuthority() {
        var c=candidate("ADD_GRANT");state(c,DesiredPresence.PRESENT);
        when(tenants.find(tenant.tenantId())).thenReturn(Optional.of(new TenantOverviewQuery.TenantSummary(tenant.tenantId(),"Tenant",
            TenantLifecycleState.ACTIVE,1,Instant.now(),Instant.now())));
        when(catalog.validateActiveTargetEntitlement(tenant,entitlement,target)).thenReturn(CatalogEntitlementReferenceQuery.Validation.VALID);
        when(identities.resolve(tenant,principal)).thenReturn(PrincipalTechnicalReferenceQuery.Result.active(principal,identity,target,"unrelated-native"));
        when(grants.addTargets(tenant,target,entitlement,principal)).thenReturn(List.of(
            new GrantProvisioningRepository.TechnicalGrantTarget(binding,"scim-2.group",1,"g",null,"p",principal)));
        assertThat(admission.check(c)).isEqualTo(ProvisioningAdmission.Decision.ADMIT);
        when(grants.addTargets(tenant,target,entitlement,principal)).thenReturn(List.of(
            new GrantProvisioningRepository.TechnicalGrantTarget(binding,"scim-2.group",1,"g",null,"different",principal)));
        assertThat(admission.check(c)).isEqualTo(ProvisioningAdmission.Decision.BLOCKED);
        when(fence.currentPlan(c,target)).thenReturn(false);
        assertThat(admission.check(c)).isEqualTo(ProvisioningAdmission.Decision.BLOCKED);
    }
    @Test void reductionRechecksSharedDemandWithoutRequiringActiveTenantOrCatalog() {
        var c=candidate("REMOVE_GRANT");state(c,DesiredPresence.ABSENT);
        when(desired.technicalGrantDemand(tenant,target,entitlement,principal)).thenReturn(DesiredProvisioningStateQuery.TechnicalGrantDemand.NOT_REQUIRED);
        assertThat(admission.check(c)).isEqualTo(ProvisioningAdmission.Decision.ADMIT);
        when(desired.technicalGrantDemand(tenant,target,entitlement,principal)).thenReturn(DesiredProvisioningStateQuery.TechnicalGrantDemand.REQUIRED);
        assertThat(admission.check(c)).isEqualTo(ProvisioningAdmission.Decision.BLOCKED);
        when(desired.technicalGrantDemand(tenant,target,entitlement,principal)).thenReturn(DesiredProvisioningStateQuery.TechnicalGrantDemand.UNAVAILABLE);
        assertThat(admission.check(c)).isEqualTo(ProvisioningAdmission.Decision.RETRY);
        verifyNoInteractions(tenants,catalog);
    }
}
