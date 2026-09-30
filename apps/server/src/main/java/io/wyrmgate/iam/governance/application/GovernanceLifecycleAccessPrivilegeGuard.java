package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.access.application.EffectiveAccessQuery;
import io.wyrmgate.iam.access.application.LifecycleAccessPrivilegeGuard;
import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.catalog.application.CatalogAccessReferenceQuery;
import io.wyrmgate.iam.catalog.application.RoleExpansionQuery;
import io.wyrmgate.iam.governance.domain.GovernancePolicyModels.PolicyKind;
import io.wyrmgate.iam.governance.domain.GovernancePolicyModels.SoDAction;
import io.wyrmgate.iam.governance.domain.GovernancePolicyModels.SoDRule;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Governance-owned mandatory SoD guard for automatic lifecycle-policy privilege increase. */
public final class GovernanceLifecycleAccessPrivilegeGuard
        implements LifecycleAccessPrivilegeGuard {

    private final GovernancePolicyService policies;
    private final CatalogAccessReferenceQuery catalog;
    private final RoleExpansionQuery roles;
    private final EffectiveAccessQuery effectiveAccess;
    private final GovernanceExceptionQuery exceptions;

    public GovernanceLifecycleAccessPrivilegeGuard(
            GovernancePolicyService policies,
            CatalogAccessReferenceQuery catalog,
            RoleExpansionQuery roles,
            EffectiveAccessQuery effectiveAccess,
            GovernanceExceptionQuery exceptions) {
        this.policies=java.util.Objects.requireNonNull(policies);
        this.catalog=java.util.Objects.requireNonNull(catalog);
        this.roles=java.util.Objects.requireNonNull(roles);
        this.effectiveAccess=java.util.Objects.requireNonNull(effectiveAccess);
        this.exceptions=java.util.Objects.requireNonNull(exceptions);
    }

    @Override
    public Result evaluate(
            TenantContext tenant,
            UUID identityId,
            AccessAssignment.TargetKind targetKind,
            UUID targetId,
            Instant at) {
        try {
            var snapshot=policies.findActiveSnapshot(tenant,PolicyKind.ACCESS_REQUEST).orElse(null);
            if(snapshot==null) return Result.unavailable("mandatory_governance_policy_unavailable");

            Set<UUID> targetEntitlements=new LinkedHashSet<>();
            if(targetKind==AccessAssignment.TargetKind.ENTITLEMENT){
                if(catalog.resolveActiveEntitlement(tenant,targetId).status()
                        != CatalogAccessReferenceQuery.Status.VALID){
                    return Result.deny();
                }
                targetEntitlements.add(targetId);
            }else{
                var expansion=roles.expandCurrent(tenant,targetId);
                if(expansion.status()!=RoleExpansionQuery.Status.AVAILABLE || expansion.paths().isEmpty()){
                    return Result.deny();
                }
                expansion.paths().forEach(path->targetEntitlements.add(path.entitlementId()));
            }

            Set<UUID> counterpartIds=new LinkedHashSet<>();
            for(SoDRule rule:snapshot.rules()){
                boolean left=targetEntitlements.contains(rule.leftEntitlementId());
                boolean right=targetEntitlements.contains(rule.rightEntitlementId());
                if(left && !right) counterpartIds.add(rule.rightEntitlementId());
                if(right && !left) counterpartIds.add(rule.leftEntitlementId());
            }
            Set<UUID> current=counterpartIds.isEmpty()
                    ? Set.of()
                    : effectiveAccess.currentEntitlementIds(tenant,identityId,counterpartIds,at);

            java.util.List<SoDRule> conflicts=new java.util.ArrayList<>();
            for(SoDRule rule:snapshot.rules()){
                boolean left=targetEntitlements.contains(rule.leftEntitlementId());
                boolean right=targetEntitlements.contains(rule.rightEntitlementId());
                if((left && right)
                        || (left && current.contains(rule.rightEntitlementId()))
                        || (right && current.contains(rule.leftEntitlementId()))){
                    conflicts.add(rule);
                }
            }
            if(conflicts.isEmpty()) return Result.authorize();

            Set<UUID> ids=conflicts.stream().map(SoDRule::id)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            var covered=exceptions.effectiveExceptionIds(tenant,identityId,ids,at);
            boolean approval=false;
            for(SoDRule conflict:conflicts){
                if(covered.containsKey(conflict.id())) continue;
                if(conflict.action()==SoDAction.DENY) return Result.deny();
                if(conflict.action()==SoDAction.REQUIRE_APPROVAL) approval=true;
            }
            return approval ? Result.requireApproval() : Result.authorize();
        }catch(RuntimeException unavailable){
            return Result.unavailable("mandatory_governance_dependency_unavailable");
        }
    }
}
