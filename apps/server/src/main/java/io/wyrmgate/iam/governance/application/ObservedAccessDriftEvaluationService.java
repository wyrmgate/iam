package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.access.application.ExactTechnicalGrantDemandQuery;
import io.wyrmgate.iam.governance.application.GovernanceObservationEvaluationRepository.Phase;
import io.wyrmgate.iam.governance.application.GovernanceObservationReporter.ObservationCondition;
import io.wyrmgate.iam.governance.application.GovernanceFindingRepository.GovernanceFinding;
import io.wyrmgate.iam.governance.domain.GovernancePolicyModels.PolicyKind;
import io.wyrmgate.iam.governance.domain.GovernancePolicyModels.SoDRule;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQuery;
import io.wyrmgate.iam.identity.application.PrincipalObservationReferenceQuery;
import io.wyrmgate.iam.integration.application.IntegrationObservedAccessQuery;
import io.wyrmgate.iam.integration.application.IntegrationObservedAccessQuery.*;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Bounded normalized positive observations and exact evidence resolution. */
public final class ObservedAccessDriftEvaluationService {
    private static final int PAGE_SIZE=100;
    // One grant can match many rules; use a smaller page for the richer evaluation.
    private static final int GRANT_PAGE_SIZE=10;
    private final IntegrationObservedAccessQuery observedAccess;
    private final PrincipalObservationReferenceQuery principalReferences;
    private final IdentityAccessReferenceQuery identities;
    private final ExactTechnicalGrantDemandQuery demand;
    private final GovernancePolicyService policies;
    private final GovernanceObservationReporter reporter;
    private final GovernanceFindingRepository findings;

    public ObservedAccessDriftEvaluationService(IntegrationObservedAccessQuery observedAccess,
            PrincipalObservationReferenceQuery principalReferences,GovernanceObservationReporter reporter,
            GovernanceFindingRepository findings,IdentityAccessReferenceQuery identities,
            ExactTechnicalGrantDemandQuery demand,GovernancePolicyService policies){
        this.observedAccess=Objects.requireNonNull(observedAccess);
        this.principalReferences=Objects.requireNonNull(principalReferences);
        this.reporter=Objects.requireNonNull(reporter);
        this.findings=Objects.requireNonNull(findings);
        this.identities=Objects.requireNonNull(identities);
        this.demand=Objects.requireNonNull(demand);
        this.policies=Objects.requireNonNull(policies);
    }

    public Progress evaluatePage(TenantContext tenant,UUID binding,Phase phase,String after,Instant now){
        return switch(phase){
            case ENTITLEMENTS -> {
                var page=observedAccess.entitlementPage(tenant,binding,after,PAGE_SIZE+1);
                for(var fact:page.subList(0,Math.min(page.size(),PAGE_SIZE))){
                    var condition=entitlementCondition(fact);
                    if(condition!=null) reporter.observe(tenant,binding,condition,now);
                }
                yield page.size()>PAGE_SIZE?new Progress(phase,page.get(PAGE_SIZE-1).providerEntitlementId()):new Progress(Phase.GRANTS,null);
            }
            case GRANTS -> {
                var page=observedAccess.grantPage(tenant,binding,after,GRANT_PAGE_SIZE+1);
                for(var fact:page.subList(0,Math.min(page.size(),GRANT_PAGE_SIZE))){
                    var condition=grantCondition(tenant,fact);
                    if(condition!=null) reporter.observe(tenant,binding,condition,now);
                    else evaluateMapped(tenant,binding,fact,now);
                }
                yield page.size()>GRANT_PAGE_SIZE?new Progress(phase,page.get(GRANT_PAGE_SIZE-1).providerGrantId()):new Progress(Phase.RESOLUTION,null);
            }
            case RESOLUTION -> {
                var page=findings.findOpenPage(tenant,binding,after,PAGE_SIZE+1);
                for(var finding:page.subList(0,Math.min(page.size(),PAGE_SIZE))){
                    if(resolved(tenant,binding,finding,now)) reporter.resolve(tenant,finding,now);
                }
                yield page.size()>PAGE_SIZE?new Progress(phase,page.get(PAGE_SIZE-1).findingKey()):new Progress(Phase.COMPLETED,null);
            }
            case COMPLETED -> new Progress(Phase.COMPLETED,null);
        };
    }

    private void evaluateMapped(TenantContext tenant,UUID binding,ObservedGrantFact grant,Instant now){
        var ref=identities.principal(tenant,grant.mappedPrincipalId());
        if(ref.status()==IdentityAccessReferenceQuery.Status.NOT_FOUND || !grant.applicationTargetId().equals(ref.applicationTargetId()))
            return;
        UUID identity=ref.identityId();
        var exactDemand=demand.demand(tenant,grant.applicationTargetId(),grant.mappedEntitlementId(),grant.mappedPrincipalId(),now);
        if(exactDemand==ExactTechnicalGrantDemandQuery.Demand.NOT_REQUIRED)
            reporter.observe(tenant,binding,new ObservationCondition("EXCESS_OBSERVED_GRANT","OBSERVED_GRANT",grant.providerGrantId(),null,
                    FindingContext.excess(identity,grant.mappedPrincipalId(),grant.mappedEntitlementId(),grant.applicationTargetId())),now);
        if(identity==null) return;
        var policy=policies.findActiveSnapshot(tenant,PolicyKind.ACCESS_REQUEST).orElse(null);
        if(policy==null) return;
        List<SoDRule> relevant=policy.rules().stream().filter(rule->counterpart(rule,grant.mappedEntitlementId())!=null).toList();
        if(relevant.isEmpty()) return;
        Set<UUID> counterparts=new LinkedHashSet<>();
        for(var rule:relevant) counterparts.add(counterpart(rule,grant.mappedEntitlementId()));
        Set<UUID> principals=principalIds(tenant,identity);
        var witnesses=observedAccess.mappedGrantWitnesses(tenant,principals,counterparts);
        var byEntitlement=new java.util.HashMap<UUID,ObservedGrantFact>();
        for(var witness:witnesses){
            if(mappedForIdentity(tenant,witness,identity)) byEntitlement.put(witness.mappedEntitlementId(),witness);
        }
        for(var rule:relevant){
            var witness=byEntitlement.get(counterpart(rule,grant.mappedEntitlementId()));
            if(witness==null) continue;
            // Preserve positive violation evidence even with an effective exception.
            reporter.observe(tenant,binding,new ObservationCondition("OBSERVED_SOD_CONFLICT","OBSERVED_GRANT",
                    grant.providerGrantId(),witness.providerGrantId(),FindingContext.sod(identity,grant.mappedPrincipalId(),
                    grant.mappedEntitlementId(),grant.applicationTargetId(),rule.id(),witness.connectorBindingId(),
                    witness.mappedPrincipalId(),witness.mappedEntitlementId())),now);
        }
    }

    private Set<UUID> principalIds(TenantContext tenant,UUID identity){
        var result=new LinkedHashSet<UUID>();
        UUID after=null;
        while(true){
            var page=principalReferences.principalIds(tenant,identity,after,100);
            result.addAll(page);
            if(result.size()>1000) throw new IllegalStateException("observation Identity Principal budget exceeded");
            if(page.size()<100) return Set.copyOf(result);
            after=page.getLast();
        }
    }

    private boolean resolved(TenantContext tenant,UUID binding,GovernanceFinding finding,Instant now){
        if("OBSERVED_ENTITLEMENT".equals(finding.subjectKind())){
            var lookup=observedAccess.entitlement(tenant,binding,finding.providerStableId());
            if(lookup.presence()==Presence.UNKNOWN) return false;
            if(lookup.presence()==Presence.TRUSTED_ABSENT) return true;
            return !Objects.equals(entitlementCondition(lookup.fact()),condition(finding));
        }
        if(!"OBSERVED_GRANT".equals(finding.subjectKind())) return false;
        var lookup=observedAccess.grant(tenant,binding,finding.providerStableId());
        if(lookup.presence()==Presence.UNKNOWN) return false;
        if(lookup.presence()==Presence.TRUSTED_ABSENT) return true;
        var grant=lookup.fact();
        if("EXCESS_OBSERVED_GRANT".equals(finding.findingType())){
            if(!sameAnchor(tenant,grant,finding.context())) return true;
            return demand.demand(tenant,grant.applicationTargetId(),grant.mappedEntitlementId(),grant.mappedPrincipalId(),now)
                    ==ExactTechnicalGrantDemandQuery.Demand.REQUIRED;
        }
        if("OBSERVED_SOD_CONFLICT".equals(finding.findingType())){
            var c=finding.context();
            if(!sameAnchor(tenant,grant,c)) return true;
            var policy=policies.findActiveSnapshot(tenant,PolicyKind.ACCESS_REQUEST).orElse(null);
            if(policy==null) return false;
            if(policy.rules().stream().noneMatch(rule->rule.id().equals(c.sodRuleId())
                    && c.relatedEntitlementId().equals(counterpart(rule,c.entitlementId())))) return true;
            var other=observedAccess.grant(tenant,c.relatedBindingId(),finding.relatedProviderStableId());
            if(other.presence()==Presence.UNKNOWN) return false;
            if(other.presence()==Presence.TRUSTED_ABSENT) return true;
            if(!Objects.equals(other.fact().mappedPrincipalId(),c.relatedPrincipalId())
                    || !Objects.equals(other.fact().mappedEntitlementId(),c.relatedEntitlementId())) return true;
            var related=identities.principal(tenant,c.relatedPrincipalId());
            if(related.status()==IdentityAccessReferenceQuery.Status.NOT_FOUND) return false;
            return !Objects.equals(related.identityId(),c.identityId())
                    || !Objects.equals(related.applicationTargetId(),other.fact().applicationTargetId());
        }
        return !Objects.equals(grantCondition(tenant,grant),condition(finding));
    }

    private boolean sameAnchor(TenantContext tenant,ObservedGrantFact grant,FindingContext c){
        if(!Objects.equals(grant.mappedPrincipalId(),c.principalId()) || !Objects.equals(grant.mappedEntitlementId(),c.entitlementId())
                || !Objects.equals(grant.applicationTargetId(),c.applicationTargetId())) return false;
        var ref=identities.principal(tenant,grant.mappedPrincipalId());
        // Missing reference is not negative evidence: keep old finding until an explicit mapping/identity change.
        if(ref.status()==IdentityAccessReferenceQuery.Status.NOT_FOUND) return true;
        return Objects.equals(ref.identityId(),c.identityId()) && Objects.equals(ref.applicationTargetId(),c.applicationTargetId());
    }

    private boolean mappedForIdentity(TenantContext tenant,ObservedGrantFact grant,UUID identity){
        if(grant.mappedEntitlementId()==null || grant.mappedPrincipalId()==null) return false;
        var ref=identities.principal(tenant,grant.mappedPrincipalId());
        return ref.status()==IdentityAccessReferenceQuery.Status.RESOLVED && identity.equals(ref.identityId())
                && grant.applicationTargetId().equals(ref.applicationTargetId());
    }

    private static UUID counterpart(SoDRule rule,UUID entitlement){
        if(rule.leftEntitlementId().equals(entitlement)) return rule.rightEntitlementId();
        if(rule.rightEntitlementId().equals(entitlement)) return rule.leftEntitlementId();
        return null;
    }

    private static ObservationCondition condition(GovernanceFinding finding){
        return new ObservationCondition(finding.findingType(),finding.subjectKind(),finding.providerStableId(),
                finding.relatedProviderStableId(),finding.context());
    }

    private static ObservationCondition entitlementCondition(ObservedEntitlementFact fact){
        return fact.mappedEntitlementId()==null?new ObservationCondition("UNMAPPED_PROVIDER_ENTITLEMENT","OBSERVED_ENTITLEMENT",fact.providerEntitlementId(),null):null;
    }

    private ObservationCondition grantCondition(TenantContext tenant,ObservedGrantFact grant){
        if(grant.mappedEntitlementId()==null) return new ObservationCondition("UNMAPPED_PROVIDER_GRANT_ENTITLEMENT","OBSERVED_GRANT",grant.providerGrantId(),grant.providerEntitlementId());
        if(grant.mappedPrincipalId()==null || principalReferences.validate(tenant,grant.mappedPrincipalId(),grant.applicationTargetId())
                !=PrincipalObservationReferenceQuery.Validation.VALID)
            return new ObservationCondition("UNRESOLVED_PROVIDER_GRANT_PRINCIPAL","OBSERVED_GRANT",grant.providerGrantId(),grant.providerPrincipalId());
        return null;
    }
    public record Progress(Phase phase,String afterKey){}
}
