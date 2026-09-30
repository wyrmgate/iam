package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.access.application.EffectiveAccessQuery;
import io.wyrmgate.iam.access.application.LifecycleAccessPrivilegeGuard;
import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.catalog.application.CatalogAccessReferenceQuery;
import io.wyrmgate.iam.catalog.application.RoleExpansionQuery;
import io.wyrmgate.iam.governance.application.LifecycleAccessEvaluationEvidenceSink.ConflictEvidence;
import io.wyrmgate.iam.governance.domain.GovernancePolicyModels.PolicyKind;
import io.wyrmgate.iam.governance.domain.GovernancePolicyModels.SoDAction;
import io.wyrmgate.iam.governance.domain.GovernancePolicyModels.SoDRule;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
    private final LifecycleAccessEvaluationEvidenceSink evidence;
    private final IdGenerator ids;

    public GovernanceLifecycleAccessPrivilegeGuard(
            GovernancePolicyService policies,
            CatalogAccessReferenceQuery catalog,
            RoleExpansionQuery roles,
            EffectiveAccessQuery effectiveAccess,
            GovernanceExceptionQuery exceptions,
            LifecycleAccessEvaluationEvidenceSink evidence,
            IdGenerator ids) {
        this.policies=java.util.Objects.requireNonNull(policies);
        this.catalog=java.util.Objects.requireNonNull(catalog);
        this.roles=java.util.Objects.requireNonNull(roles);
        this.effectiveAccess=java.util.Objects.requireNonNull(effectiveAccess);
        this.exceptions=java.util.Objects.requireNonNull(exceptions);
        this.evidence=java.util.Objects.requireNonNull(evidence);
        this.ids=java.util.Objects.requireNonNull(ids);
    }

    @Override
    public Result evaluate(
            TenantContext tenant,
            UUID identityId,
            UUID lifecycleRuleId,
            AccessAssignment.TargetKind targetKind,
            UUID targetId,
            Instant at,
            UUID correlationId,
            UUID causationId) {
        UUID policyVersionId = null;
        try {
            var snapshot=policies.findActiveSnapshot(tenant,PolicyKind.ACCESS_REQUEST).orElse(null);
            if(snapshot==null){
                return record(
                        tenant, identityId, lifecycleRuleId, null,
                        targetKind, targetId,
                        Result.unavailable("mandatory_governance_policy_unavailable"),
                        List.of(), at, correlationId, causationId);
            }
            policyVersionId=snapshot.version().id();

            Set<UUID> targetEntitlements=new LinkedHashSet<>();
            if(targetKind==AccessAssignment.TargetKind.ENTITLEMENT){
                if(catalog.resolveActiveEntitlement(tenant,targetId).status()
                        != CatalogAccessReferenceQuery.Status.VALID){
                    return record(
                            tenant, identityId, lifecycleRuleId, policyVersionId,
                            targetKind, targetId,
                            new Result(Decision.DENY, "target_not_assignable"),
                            List.of(), at, correlationId, causationId);
                }
                targetEntitlements.add(targetId);
            }else{
                var expansion=roles.expandCurrent(tenant,targetId);
                if(expansion.status()!=RoleExpansionQuery.Status.AVAILABLE || expansion.paths().isEmpty()){
                    return record(
                            tenant, identityId, lifecycleRuleId, policyVersionId,
                            targetKind, targetId,
                            new Result(Decision.DENY, "target_not_assignable"),
                            List.of(), at, correlationId, causationId);
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

            List<SoDRule> conflicts=new ArrayList<>();
            for(SoDRule rule:snapshot.rules()){
                boolean left=targetEntitlements.contains(rule.leftEntitlementId());
                boolean right=targetEntitlements.contains(rule.rightEntitlementId());
                if((left && right)
                        || (left && current.contains(rule.rightEntitlementId()))
                        || (right && current.contains(rule.leftEntitlementId()))){
                    conflicts.add(rule);
                }
            }

            Set<UUID> conflictIds=conflicts.stream().map(SoDRule::id)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            var covered=conflictIds.isEmpty()
                    ? java.util.Map.<UUID,UUID>of()
                    : exceptions.effectiveExceptionIds(tenant,identityId,conflictIds,at);

            List<ConflictEvidence> conflictEvidence=new ArrayList<>();
            boolean approval=false;
            boolean deny=false;
            for(SoDRule conflict:conflicts){
                UUID exceptionId=covered.get(conflict.id());
                conflictEvidence.add(new ConflictEvidence(
                        conflict.id(),
                        conflict.severity(),
                        conflict.action(),
                        exceptionId));
                if(exceptionId!=null) continue;
                if(conflict.action()==SoDAction.DENY) deny=true;
                if(conflict.action()==SoDAction.REQUIRE_APPROVAL) approval=true;
            }

            Result result;
            if(deny){
                result=new Result(Decision.DENY,"sod_denied");
            }else if(approval){
                result=new Result(Decision.REQUIRE_APPROVAL,"sod_approval_required");
            }else{
                result=Result.authorize();
            }
            return record(
                    tenant, identityId, lifecycleRuleId, policyVersionId,
                    targetKind, targetId, result,
                    conflictEvidence, at, correlationId, causationId);
        }catch(RuntimeException unavailable){
            return record(
                    tenant, identityId, lifecycleRuleId, policyVersionId,
                    targetKind, targetId,
                    Result.unavailable("mandatory_governance_dependency_unavailable"),
                    List.of(), at, correlationId, causationId);
        }
    }

    private Result record(
            TenantContext tenant,
            UUID identityId,
            UUID lifecycleRuleId,
            UUID policyVersionId,
            AccessAssignment.TargetKind targetKind,
            UUID targetId,
            Result result,
            List<ConflictEvidence> conflicts,
            Instant at,
            UUID correlationId,
            UUID causationId) {
        evidence.record(
                tenant,
                ids.nextId(),
                identityId,
                lifecycleRuleId,
                policyVersionId,
                targetKind,
                targetId,
                result.decision(),
                result.code(),
                conflicts,
                at,
                correlationId,
                causationId);
        return result;
    }
}
