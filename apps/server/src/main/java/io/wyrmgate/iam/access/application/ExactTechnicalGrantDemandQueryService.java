package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.catalog.application.CatalogAccessReferenceQuery;
import io.wyrmgate.iam.catalog.application.CatalogAccessTargetAvailabilityQuery;
import io.wyrmgate.iam.catalog.application.RoleExpansionQuery;
import io.wyrmgate.iam.catalog.domain.CatalogAccessTargetKind;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQuery;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class ExactTechnicalGrantDemandQueryService implements ExactTechnicalGrantDemandQuery {
    private final AccessAssignmentRepository assignments;
    private final IdentityAccessReferenceQuery identities;
    private final CatalogAccessReferenceQuery references;
    private final CatalogAccessTargetAvailabilityQuery catalog;
    private final RoleExpansionQuery roles;
    private final EffectiveAccessSemanticSupport semantics;

    public ExactTechnicalGrantDemandQueryService(AccessAssignmentRepository assignments, IdentityAccessReferenceQuery identities,
            CatalogAccessReferenceQuery references, CatalogAccessTargetAvailabilityQuery catalog, RoleExpansionQuery roles,
            EffectiveAccessSemanticSupport semantics){
        this.assignments=Objects.requireNonNull(assignments);
        this.identities=Objects.requireNonNull(identities);
        this.references=Objects.requireNonNull(references);
        this.catalog=Objects.requireNonNull(catalog);
        this.roles=Objects.requireNonNull(roles);
        this.semantics=Objects.requireNonNull(semantics);
    }

    @Override
    public Demand demand(TenantContext tenant,UUID target,UUID entitlement,UUID principal,Instant at){
        try{
            var ref=identities.principal(tenant,principal);
            if(ref.status()==IdentityAccessReferenceQuery.Status.NOT_FOUND || !target.equals(ref.applicationTargetId())) return Demand.UNAVAILABLE;
            var entitlementRef=references.resolveActiveEntitlement(tenant,entitlement);
            if(entitlementRef.status()==CatalogAccessReferenceQuery.Status.NOT_FOUND) return Demand.UNAVAILABLE;
            if(entitlementRef.status()!=CatalogAccessReferenceQuery.Status.VALID) return Demand.NOT_REQUIRED;
            if(!target.equals(entitlementRef.applicationTargetId())) return Demand.UNAVAILABLE;
            if(ref.status()==IdentityAccessReferenceQuery.Status.UNCORRELATED) return Demand.NOT_REQUIRED;
            var identity=identities.accessStatus(tenant,ref.identityId());
            if(identity.status()==IdentityAccessReferenceQuery.AccessStatus.NOT_FOUND) return Demand.UNAVAILABLE;
            if(identity.status()!=IdentityAccessReferenceQuery.AccessStatus.ACCESS_ELIGIBLE) return Demand.NOT_REQUIRED;
            Instant from=at.truncatedTo(ChronoUnit.MICROS),until=from.plus(1,ChronoUnit.MICROS);
            Instant afterAt=null;
            UUID afterId=null;
            int scanned=0,paths=0;
            boolean unresolvedSelection=false;
            IdentityAccessReferenceQuery.PrincipalSelection selection=null;
            while(true){
                var page=assignments.findAdmissionPage(tenant,ref.identityId(),identity.accessEligibilityEpoch(),
                        Set.of(entitlement),from,until,afterAt,afterId,200);
                for(var assignment:page){
                    if(++scanned>1000) return Demand.UNAVAILABLE;
                    if(!assignment.isSemanticallyEffectiveAt(at)) continue;
                    UUID assignmentTarget=assignment.targetKind()==AccessAssignment.TargetKind.ROLE?assignment.roleId():assignment.entitlementId();
                    var availability=catalog.currentAvailability(tenant,CatalogAccessTargetKind.valueOf(assignment.targetKind().name()),assignmentTarget).orElse(null);
                    if(availability==null) return Demand.UNAVAILABLE;
                    if(availability.terminal()) continue;
                    if(!availability.assignable()) return Demand.UNAVAILABLE;
                    if(assignment.targetKind()==AccessAssignment.TargetKind.ROLE){
                        var expansion=roles.expandCurrent(tenant,assignment.roleId());
                        if(expansion.status()!=RoleExpansionQuery.Status.AVAILABLE || expansion.paths().isEmpty()) return Demand.UNAVAILABLE;
                        paths+=expansion.paths().size();
                        if(paths>10000) return Demand.UNAVAILABLE;
                        if(expansion.paths().stream().noneMatch(p->p.entitlementId().equals(entitlement) && p.applicationTargetId().equals(target))) continue;
                        if(!semantics.specificRoleCompatible(tenant,assignment,expansion)) continue;
                    }else if(!semantics.specificDirectCompatible(tenant,assignment)) continue;
                    if(assignment.principalConstraintKind()==AccessAssignment.PrincipalConstraintKind.SPECIFIC){
                        if(principal.equals(assignment.specificPrincipalId())) return Demand.REQUIRED;
                    }else{
                        if(selection==null) selection=identities.selectUniqueActivePrincipal(tenant,ref.identityId(),target);
                        if(selection.status()!=IdentityAccessReferenceQuery.PrincipalSelectionStatus.RESOLVED) unresolvedSelection=true;
                        else if(principal.equals(selection.principalId())) return Demand.REQUIRED;
                    }
                }
                if(page.size()<200) break;
                afterAt=page.getLast().createdAt(); afterId=page.getLast().id();
            }
            return unresolvedSelection?Demand.UNAVAILABLE:Demand.NOT_REQUIRED;
        }catch(RuntimeException unavailable){return Demand.UNAVAILABLE;}
    }
}
