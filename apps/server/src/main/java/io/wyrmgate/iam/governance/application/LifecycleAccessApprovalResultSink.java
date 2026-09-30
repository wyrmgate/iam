package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.application.ApprovalModels.*;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.OutboxEvent;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.UUID;

/** Publishes only a trigger; Access revalidates current state before any privilege mutation. */
public final class LifecycleAccessApprovalResultSink implements ApprovalResultSink {
    public static final String APPROVED =
            "governance.lifecycle-access-approval-approved";

    private final LifecycleAccessApprovalCandidateRepository candidates;
    private final JdbcOutboxRepository outbox;
    private final IdGenerator ids;

    public LifecycleAccessApprovalResultSink(
            LifecycleAccessApprovalCandidateRepository candidates,
            JdbcOutboxRepository outbox,
            IdGenerator ids) {
        this.candidates=java.util.Objects.requireNonNull(candidates);
        this.outbox=java.util.Objects.requireNonNull(outbox);
        this.ids=java.util.Objects.requireNonNull(ids);
    }

    @Override
    public void approvalResolved(TenantContext tenant, ApprovalCase approvalCase) {
        if(approvalCase.subjectKind()!=SubjectKind.LIFECYCLE_ACCESS_CANDIDATE
                || approvalCase.state()!=CaseState.APPROVED) return;
        var candidate=candidates.findById(tenant,approvalCase.subjectId())
                .orElseThrow(()->new IllegalStateException("lifecycle approval candidate missing"));
        UUID eventId=ids.nextId();
        outbox.append(tenant,new OutboxEvent(
                eventId,APPROVED,1,"identity",candidate.identityId(),
                approvalCase.revision(),approvalCase.updatedAt(),
                candidate.correlationId(),approvalCase.id(),"{}"),
                approvalCase.updatedAt());
    }
}
