package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.access.application.LifecycleAccessApprovalCommand;
import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.governance.application.ApprovalModels.*;
import io.wyrmgate.iam.governance.application.LifecycleAccessApprovalCandidateRepository.Candidate;
import io.wyrmgate.iam.governance.domain.GovernancePolicyModels.PolicyKind;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class GovernanceLifecycleAccessApprovalService
        implements LifecycleAccessApprovalCommand {
    private final GovernancePolicyService policies;
    private final LifecycleAccessApprovalCandidateRepository candidates;
    private final ApprovalCaseStartService approvals;
    private final ApprovalRepository approvalRepository;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;

    public GovernanceLifecycleAccessApprovalService(
            GovernancePolicyService policies,
            LifecycleAccessApprovalCandidateRepository candidates,
            ApprovalCaseStartService approvals,
            ApprovalRepository approvalRepository,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this.policies=java.util.Objects.requireNonNull(policies);
        this.candidates=java.util.Objects.requireNonNull(candidates);
        this.approvals=java.util.Objects.requireNonNull(approvals);
        this.approvalRepository=java.util.Objects.requireNonNull(approvalRepository);
        this.ids=java.util.Objects.requireNonNull(ids);
        this.transactions=java.util.Objects.requireNonNull(transactions);
    }

    @Override
    public boolean currentApprovalSatisfied(
            TenantContext tenant, UUID identityId, UUID lifecycleRuleId,
            AccessAssignment.TargetKind targetKind, UUID targetId) {
        var snapshot=policies.findActiveSnapshot(tenant,PolicyKind.ACCESS_REQUEST).orElse(null);
        if(snapshot==null || snapshot.approvalStages().isEmpty()) return false;
        PlanSpec plan=plan(snapshot);
        String hash=ApprovalCaseStartService.contentHash(plan);
        Candidate candidate=candidates.findByContext(
                tenant,identityId,lifecycleRuleId,targetKind,targetId,
                snapshot.version().id(),hash).orElse(null);
        if(candidate==null) return false;
        return approvalRepository.findLatestBySubject(
                        tenant,SubjectKind.LIFECYCLE_ACCESS_CANDIDATE,candidate.id())
                .map(c->c.state()==CaseState.APPROVED)
                .orElse(false);
    }

    @Override
    public void requestApproval(
            TenantContext tenant, UUID identityId, UUID lifecycleRuleId,
            AccessAssignment.TargetKind targetKind, UUID targetId, Instant at,
            UUID correlationId, UUID causationId) {
        var snapshot=policies.findActiveSnapshot(tenant,PolicyKind.ACCESS_REQUEST)
                .orElseThrow(()->new IllegalStateException("mandatory governance policy unavailable"));
        PlanSpec plan=plan(snapshot);
        String hash=ApprovalCaseStartService.contentHash(plan);
        transactions.required(()->{
            Candidate candidate=candidates.findByContext(
                    tenant,identityId,lifecycleRuleId,targetKind,targetId,
                    snapshot.version().id(),hash).orElseGet(()->{
                        Candidate created=new Candidate(
                                ids.nextId(),identityId,lifecycleRuleId,targetKind,targetId,
                                snapshot.version().id(),hash,correlationId,causationId,at);
                        candidates.insert(tenant,created);
                        return candidates.findByContext(
                                tenant,identityId,lifecycleRuleId,targetKind,targetId,
                                snapshot.version().id(),hash).orElse(created);
                    });
            approvals.start(
                    tenant,SubjectKind.LIFECYCLE_ACCESS_CANDIDATE,candidate.id(),
                    identityId,plan,at);
            return null;
        });
    }

    static PlanSpec plan(io.wyrmgate.iam.governance.domain.GovernancePolicyModels.PolicySnapshot snapshot) {
        if(snapshot.approvalStages().isEmpty()) {
            throw new IllegalStateException("governance approval plan unavailable");
        }
        return new PlanSpec(snapshot.approvalStages().stream()
                .map(stage->new StageSpec(
                        stage.stage().decisionMode(),
                        stage.approvers().stream()
                                .map(a->a.approverIdentityId()).toList()))
                .toList());
    }
}
