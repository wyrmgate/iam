package io.wyrmgate.iam.governance.persistence;

import io.wyrmgate.iam.governance.application.ApprovalOutcomeSink;
import io.wyrmgate.iam.governance.domain.ApprovalPlan;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.OutboxEvent;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class JdbcApprovalOutcomeSink
        implements ApprovalOutcomeSink {

    private final JdbcOutboxRepository outbox;
    private final IdGenerator ids;

    public JdbcApprovalOutcomeSink(
            JdbcOutboxRepository outbox,
            IdGenerator ids) {
        this.outbox = Objects.requireNonNull(
                outbox, "outbox");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    public void outcomeChanged(
            TenantContext tenant,
            ApprovalPlan plan,
            UUID correlationId,
            UUID causationId) {
        Instant now = plan.updatedAt();
        String payload = """
                {"subjectKind":"%s","subjectId":"%s","outcome":"%s"}
                """.formatted(
                        plan.subject().kind().name(),
                        plan.subject().id(),
                        plan.lifecycleState().name())
                .trim();
        outbox.append(
                tenant,
                new OutboxEvent(
                        ids.nextId(),
                        ApprovalOutcomeSink.eventType(plan.subject().kind()),
                        1,
                        "approval-plan",
                        plan.id(),
                        plan.revision(),
                        now,
                        correlationId,
                        causationId,
                        payload),
                now);
    }
}
