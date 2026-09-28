package io.wyrmgate.iam.governance.persistence;

import io.wyrmgate.iam.governance.application.ApprovalOutcomeFactSink;
import io.wyrmgate.iam.governance.domain.ApprovalCase;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.OutboxEvent;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class JdbcApprovalOutcomeFactSink
        implements ApprovalOutcomeFactSink {

    private final JdbcOutboxRepository outbox;
    private final IdGenerator ids;

    public JdbcApprovalOutcomeFactSink(
            JdbcOutboxRepository outbox, IdGenerator ids) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    public void terminalOutcome(
            TenantContext tenant,
            ApprovalCase approvalCase,
            UUID correlationId,
            UUID causationId) {
        if (approvalCase.lifecycleState()
                != ApprovalCase.LifecycleState.APPROVED
                && approvalCase.lifecycleState()
                        != ApprovalCase.LifecycleState.REJECTED) {
            throw new IllegalArgumentException(
                    "approval outcome fact requires terminal decision state");
        }
        String suffix = approvalCase.subjectType().name()
                .toLowerCase(Locale.ROOT)
                .replace('_', '-');
        String payload = """
                {"approvalCaseId":"%s","subjectType":"%s","subjectId":"%s","subjectRevision":%d,"outcome":"%s"}
                """.formatted(
                approvalCase.id(),
                approvalCase.subjectType().name(),
                approvalCase.subjectId(),
                approvalCase.subjectRevision(),
                approvalCase.lifecycleState().name()).trim();
        outbox.append(
                tenant,
                new OutboxEvent(
                        ids.nextId(),
                        "governance.approval-outcome." + suffix,
                        1,
                        "governance-approval-case",
                        approvalCase.id(),
                        approvalCase.revision(),
                        approvalCase.updatedAt(),
                        correlationId,
                        causationId,
                        payload),
                approvalCase.updatedAt());
    }
}
