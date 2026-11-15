package io.wyrmgate.iam.governance.application;
import io.wyrmgate.iam.access.application.AccessAdmissionConflictQuery;
import io.wyrmgate.iam.access.application.AccessAssignmentCommandException;
import io.wyrmgate.iam.access.application.AccessPrivilegeIncreaseGuard;
import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.catalog.application.AccessTargetFingerprint;
import io.wyrmgate.iam.catalog.application.CatalogAccessReferenceQuery;
import io.wyrmgate.iam.catalog.application.RoleExpansionQuery;
import io.wyrmgate.iam.governance.domain.GovernancePolicyModels.PolicyKind;
import io.wyrmgate.iam.governance.domain.GovernancePolicyModels.SoDAction;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Manual authority is not request approval authority. Enforce every applicable SoD rule. */
public final class GovernanceAccessPrivilegeIncreaseGuard implements AccessPrivilegeIncreaseGuard {
    private final GovernancePolicyService policies;
    private final CatalogAccessReferenceQuery catalog;
    private final RoleExpansionQuery roles;
    private final AccessAdmissionConflictQuery conflicts;
    private final GovernanceExceptionQuery exceptions;
    public GovernanceAccessPrivilegeIncreaseGuard(GovernancePolicyService policies, CatalogAccessReferenceQuery catalog,
            RoleExpansionQuery roles, AccessAdmissionConflictQuery conflicts, GovernanceExceptionQuery exceptions) {
        this.policies=java.util.Objects.requireNonNull(policies);
        this.catalog=java.util.Objects.requireNonNull(catalog);
        this.roles=java.util.Objects.requireNonNull(roles);
        this.conflicts=java.util.Objects.requireNonNull(conflicts);
        this.exceptions=java.util.Objects.requireNonNull(exceptions);
    }
    @Override
    public void requireAllowed(TenantContext tenant, UUID identityId, AccessAssignment.TargetKind kind,
            UUID targetId, String fingerprint, Instant from, Instant until, Instant at) {
        try {
            var policy=policies.findActiveSnapshot(tenant,PolicyKind.ACCESS_REQUEST)
                    .orElseThrow(()->unavailable());
            Set<UUID> requested=new LinkedHashSet<>();
            String actual;
            if(kind==AccessAssignment.TargetKind.ENTITLEMENT){
                var ref=catalog.resolveActiveEntitlement(tenant,targetId);
                if(ref.status()!=CatalogAccessReferenceQuery.Status.VALID) throw unavailable();
                actual=AccessTargetFingerprint.entitlement(targetId,ref.applicationTargetId());
                requested.add(targetId);
            }else{
                var expansion=roles.expandCurrent(tenant,targetId);
                if(expansion.status()!=RoleExpansionQuery.Status.AVAILABLE || expansion.paths().isEmpty()) throw unavailable();
                actual=AccessTargetFingerprint.role(expansion);
                expansion.paths().forEach(path->requested.add(path.entitlementId()));
            }
            if(!actual.equals(fingerprint)) throw unavailable();
            Set<UUID> counterparts=new LinkedHashSet<>();
            for(var rule:policy.rules()){
                if(requested.contains(rule.leftEntitlementId())) counterparts.add(rule.rightEntitlementId());
                if(requested.contains(rule.rightEntitlementId())) counterparts.add(rule.leftEntitlementId());
            }
            counterparts.removeAll(requested);
            var held=conflicts.overlappingEntitlementIds(tenant,identityId,counterparts,from,until);
            var matches=policy.rules().stream().filter(rule->
                (requested.contains(rule.leftEntitlementId()) &&
                    (requested.contains(rule.rightEntitlementId()) || held.contains(rule.rightEntitlementId())))
                || (requested.contains(rule.rightEntitlementId()) && held.contains(rule.leftEntitlementId()))).toList();
            var ids=matches.stream().map(rule->rule.id()).collect(java.util.stream.Collectors.toSet());
            var covered=exceptions.effectiveExceptionIds(tenant,identityId,ids,at);
            boolean approval=false;
            for(var rule:matches){
                if(covered.containsKey(rule.id())) continue;
                if(rule.action()==SoDAction.DENY) throw new AccessAssignmentCommandException("sod_denied","Current SoD policy denies this access.");
                approval=true;
            }
            if(approval) throw new AccessAssignmentCommandException("sod_approval_required","Use an approved AccessRequest for this access.");
        } catch(AccessAssignmentCommandException rejected){throw rejected;}
        catch(RuntimeException failure){throw unavailable();}
    }
    private static AccessAssignmentCommandException unavailable(){
        return new AccessAssignmentCommandException("mandatory_governance_dependency_unavailable","Current mandatory Governance evaluation unavailable.");
    }
}
