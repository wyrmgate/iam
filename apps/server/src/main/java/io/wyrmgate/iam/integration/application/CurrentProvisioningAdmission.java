package io.wyrmgate.iam.integration.application;

import io.wyrmgate.iam.access.application.DesiredProvisioningStateQuery;
import io.wyrmgate.iam.access.application.DesiredStateProjectionRepository.DesiredPresence;
import io.wyrmgate.iam.catalog.application.CatalogEntitlementReferenceQuery;
import io.wyrmgate.iam.catalog.application.CatalogTargetReferenceQuery;
import io.wyrmgate.iam.identity.application.PrincipalTechnicalReferenceQuery;
import io.wyrmgate.iam.integration.application.ConnectorWorkRepository.ProvisioningCandidate;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import io.wyrmgate.iam.tenancy.application.TenantOverviewQuery;
import io.wyrmgate.iam.tenancy.domain.TenantLifecycleState;
import java.util.Map;
import java.util.UUID;

/** Shared local/remote admission through capability-owned current reads and Integration plan fences. */
public final class CurrentProvisioningAdmission implements ProvisioningAdmission {
    private final ProvisioningPlanFence fence;
    private final DesiredProvisioningStateQuery desired;
    private final GrantProvisioningRepository grants;
    private final PrincipalProvisioningRepository principals;
    private final PrincipalTechnicalReferenceQuery identities;
    private final CatalogEntitlementReferenceQuery catalog;
    private final CatalogTargetReferenceQuery targets;
    private final TenantOverviewQuery tenants;

    public CurrentProvisioningAdmission(ProvisioningPlanFence fence,DesiredProvisioningStateQuery desired,
            GrantProvisioningRepository grants,PrincipalProvisioningRepository principals,
            PrincipalTechnicalReferenceQuery identities,CatalogEntitlementReferenceQuery catalog,
            CatalogTargetReferenceQuery targets,TenantOverviewQuery tenants) {
        this.fence=java.util.Objects.requireNonNull(fence);this.desired=java.util.Objects.requireNonNull(desired);
        this.grants=java.util.Objects.requireNonNull(grants);this.principals=java.util.Objects.requireNonNull(principals);
        this.identities=java.util.Objects.requireNonNull(identities);this.catalog=java.util.Objects.requireNonNull(catalog);
        this.targets=java.util.Objects.requireNonNull(targets);this.tenants=java.util.Objects.requireNonNull(tenants);
    }
    @Override public Decision check(ProvisioningCandidate c) {
        TenantContext tenant=new TenantContext(c.tenantId());
        try {
            return switch(c.subjectKind()) {
                case "DESIRED_GRANT" -> grant(tenant,c);
                case "DESIRED_PRINCIPAL" -> principal(tenant,c);
                default -> Decision.BLOCKED;
            };
        } catch(IllegalArgumentException malformed) {
            return Decision.BLOCKED;
        } catch(RuntimeException unavailable) {
            return Decision.RETRY;
        }
    }
    private Decision grant(TenantContext tenant,ProvisioningCandidate c) {
        var snapshot=desired.desiredGrant(tenant,c.subjectId());
        if(snapshot.status()==DesiredProvisioningStateQuery.Status.UNAVAILABLE)return Decision.RETRY;
        if(snapshot.status()!=DesiredProvisioningStateQuery.Status.CURRENT)return Decision.BLOCKED;
        var g=snapshot.grant();
        if(g.desiredRevision()!=c.desiredRevision()||!fence.currentPlan(c,g.applicationTargetId()))return Decision.BLOCKED;
        UUID principal=uuid(c.payload(),"principalId"), entitlement=uuid(c.payload(),"entitlementId");
        if(!g.entitlementId().equals(entitlement)||text(c.payload(),"providerPrincipalId")==null||text(c.payload(),"providerEntitlementId")==null)return Decision.BLOCKED;
        if("ADD_GRANT".equals(c.operationType())) {
            if(g.desiredState()!=DesiredPresence.PRESENT||!principal.equals(g.principalId())||!activeTenant(tenant)
                    ||catalog.validateActiveTargetEntitlement(tenant,entitlement,g.applicationTargetId())!=CatalogEntitlementReferenceQuery.Validation.VALID)return Decision.BLOCKED;
            var subject=identities.resolve(tenant,principal);
            if(subject.status()!=PrincipalTechnicalReferenceQuery.Status.ACTIVE||!g.identityId().equals(subject.identityId())||!g.applicationTargetId().equals(subject.applicationTargetId()))return Decision.BLOCKED;
            var routes=grants.addTargets(tenant,g.applicationTargetId(),entitlement,principal);
            return routes.size()==1&&same(c,routes.getFirst())?Decision.ADMIT:Decision.BLOCKED;
        }
        if(!"REMOVE_GRANT".equals(c.operationType())||!fence.removalMappingSafe(c,principal,entitlement))return Decision.BLOCKED;
        var demand=desired.technicalGrantDemand(tenant,g.applicationTargetId(),entitlement,principal);
        if(demand==DesiredProvisioningStateQuery.TechnicalGrantDemand.UNAVAILABLE)return Decision.RETRY;
        if(demand==DesiredProvisioningStateQuery.TechnicalGrantDemand.NOT_REQUIRED)return Decision.ADMIT;
        // A still-needed technical grant may only leave an obsolete route after exact replacement success.
        if(g.desiredState()!=DesiredPresence.PRESENT||!principal.equals(g.principalId()))return Decision.BLOCKED;
        var routes=grants.addTargets(tenant,g.applicationTargetId(),entitlement,principal);
        if(routes.size()!=1||same(c,routes.getFirst()))return Decision.BLOCKED;
        return fence.replacementSucceeded(c,routes.getFirst())?Decision.ADMIT:Decision.RETRY;
    }
    private Decision principal(TenantContext tenant,ProvisioningCandidate c) {
        var snapshot=desired.desiredPrincipal(tenant,c.subjectId());
        if(snapshot.status()==DesiredProvisioningStateQuery.Status.UNAVAILABLE)return Decision.RETRY;
        if(snapshot.status()!=DesiredProvisioningStateQuery.Status.CURRENT)return Decision.BLOCKED;
        var d=snapshot.principal();
        if(d.desiredRevision()!=c.desiredRevision()||!d.identityId().equals(uuid(c.payload(),"identityId"))
                ||!d.applicationTargetId().equals(uuid(c.payload(),"applicationTargetId"))||!fence.currentPlan(c,d.applicationTargetId()))return Decision.BLOCKED;
        boolean upsert="UPSERT_PRINCIPAL".equals(c.operationType());
        boolean disable="DISABLE_PRINCIPAL".equals(c.operationType())||"DEACTIVATE_PRINCIPAL".equals(c.operationType());
        if(!upsert&&!disable)return Decision.BLOCKED;
        if(upsert&&(d.desiredState()!=DesiredPresence.PRESENT||!activeTenant(tenant)
                ||targets.validateActiveTarget(tenant,d.applicationTargetId())!=CatalogTargetReferenceQuery.Validation.VALID))return Decision.BLOCKED;
        if(disable&&d.desiredState()!=DesiredPresence.ABSENT)return Decision.BLOCKED;
        String provider=text(c.payload(),"providerStableId");
        if(provider==null) {
            if(!upsert||!identities.forIdentityTarget(tenant,d.identityId(),d.applicationTargetId()).isEmpty())return Decision.BLOCKED;
            var routes=principals.creationTargets(tenant,d.applicationTargetId());
            if(routes.size()!=1)return Decision.BLOCKED;
            var route=routes.getFirst();
            String template=route.userNameTemplate();
            return route.connectorBindingId().equals(c.connectorBindingId())&&template!=null
                &&template.contains("{identityId}")&&template.replace("{identityId}",d.identityId().toString()).equals(text(c.payload(),"userName"))
                ?Decision.ADMIT:Decision.BLOCKED;
        }
        UUID principalId=uuid(c.payload(),"principalId");
        var subject=identities.resolve(tenant,principalId);
        if(subject.status()==PrincipalTechnicalReferenceQuery.Status.UNAVAILABLE)return Decision.RETRY;
        if(!d.identityId().equals(subject.identityId())||!d.applicationTargetId().equals(subject.applicationTargetId()))return Decision.BLOCKED;
        var routes=principals.existingTargets(tenant,d.applicationTargetId(),d.id(),principalId,subject.nativePrincipalKey());
        boolean exact=routes.stream().anyMatch(r->r.connectorBindingId().equals(c.connectorBindingId())&&provider.equals(r.providerPrincipalId()));
        return exact&&(!upsert||routes.size()==1)?Decision.ADMIT:Decision.BLOCKED;
    }
    private boolean activeTenant(TenantContext tenant) {
        return tenants.find(tenant.tenantId()).filter(t->t.lifecycleState()==TenantLifecycleState.ACTIVE).isPresent();
    }
    private static boolean same(ProvisioningCandidate c,GrantProvisioningRepository.TechnicalGrantTarget r) {
        return r.connectorBindingId().equals(c.connectorBindingId())&&r.contractId().equals(c.contractId())
                &&r.contractVersion()==c.contractVersion()&&r.providerPrincipalId().equals(text(c.payload(),"providerPrincipalId"))
                &&r.providerEntitlementId().equals(text(c.payload(),"providerEntitlementId"));
    }
    private static UUID uuid(Map<String,Object> payload,String key) {
        String v=text(payload,key);if(v==null)throw new IllegalArgumentException("missing governed route identity");return UUID.fromString(v);
    }
    private static String text(Map<String,Object> payload,String key) {
        Object value=payload.get(key);return value instanceof String s&&!s.isBlank()?s:null;
    }
}
