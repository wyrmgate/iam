package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.access.application.AccessAdmissionConflictQuery;
import io.wyrmgate.iam.governance.application.GovernanceObservationReporter.ObservationCondition;
import io.wyrmgate.iam.governance.domain.GovernanceExceptionModels.LifecycleState;
import io.wyrmgate.iam.governance.domain.GovernancePolicyModels.PolicyKind;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Current authoritative exposure after exact exception validity ends; no automatic revocation. */
public final class ExceptionExposureProcessingService {
    private final GovernanceExceptionRepository exceptions;
    private final GovernanceExceptionQuery coverage;
    private final GovernancePolicyService policies;
    private final AccessAdmissionConflictQuery access;
    private final GovernanceObservationReporter reporter;
    private final GovernanceFindingRepository findings;
    private final TransactionExecutor transactions;

    public ExceptionExposureProcessingService(GovernanceExceptionRepository exceptions,GovernanceExceptionQuery coverage,
            GovernancePolicyService policies,AccessAdmissionConflictQuery access,GovernanceObservationReporter reporter,
            GovernanceFindingRepository findings,TransactionExecutor transactions){
        this.exceptions=Objects.requireNonNull(exceptions);
        this.coverage=Objects.requireNonNull(coverage);
        this.policies=Objects.requireNonNull(policies);
        this.access=Objects.requireNonNull(access);
        this.reporter=Objects.requireNonNull(reporter);
        this.findings=Objects.requireNonNull(findings);
        this.transactions=Objects.requireNonNull(transactions);
    }

    public void evaluate(TenantContext tenant,UUID exceptionId,Instant at){
        transactions.required(()->{
            var exception=exceptions.findById(tenant,exceptionId).orElseThrow();
            if(exception.validUntil().isAfter(at) || (exception.lifecycleState()!=LifecycleState.APPROVED
                    && exception.lifecycleState()!=LifecycleState.EXPIRED)) return null;
            var policy=policies.findActiveSnapshot(tenant,PolicyKind.ACCESS_REQUEST).orElse(null);
            if(policy==null) return null;
            var rule=policy.rules().stream().filter(r->r.id().equals(exception.sodRuleId())).findFirst().orElse(null);
            var condition=new ObservationCondition("EXPIRED_EXCEPTION_EXPOSURE","GOVERNANCE_EXCEPTION",null,null,
                    FindingContext.exception(exception.subjectIdentityId(),exception.sodRuleId(),exception.id()));
            boolean exposed=false;
            if(rule!=null && coverage.effectiveExceptionIds(tenant,exception.subjectIdentityId(),Set.of(rule.id()),at).isEmpty()){
                var pair=Set.of(rule.leftEntitlementId(),rule.rightEntitlementId());
                Instant point=at.truncatedTo(ChronoUnit.MICROS);
                exposed=access.overlappingEntitlementIds(tenant,exception.subjectIdentityId(),pair,point,point.plus(1,ChronoUnit.MICROS)).containsAll(pair);
            }
            if(exposed) reporter.observe(tenant,null,condition,at);
            else{
                var existing=findings.findByKey(tenant,GovernanceObservationReportingService.findingKey(null,condition)).orElse(null);
                if(existing!=null && "OPEN".equals(existing.lifecycleState())) reporter.resolve(tenant,existing,at);
            }
            return null;
        });
    }
}
