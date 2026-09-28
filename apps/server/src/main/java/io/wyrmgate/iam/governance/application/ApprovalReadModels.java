package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.application.ApprovalModels.ApprovalApprover;
import io.wyrmgate.iam.governance.application.ApprovalModels.ApprovalCase;
import io.wyrmgate.iam.governance.application.ApprovalModels.ApprovalDecision;
import io.wyrmgate.iam.governance.application.ApprovalModels.ApprovalPlan;
import io.wyrmgate.iam.governance.application.ApprovalModels.ApprovalStage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ApprovalReadModels {
    private ApprovalReadModels() {}

    public record InboxPosition(Instant createdAt, UUID id) {}

    public record InboxPage(
            List<ApprovalCase> items,
            InboxPosition nextPosition) {
        public InboxPage {
            items = List.copyOf(items);
        }
    }

    public record StageEvidence(
            ApprovalStage stage,
            List<ApprovalApprover> approvers,
            List<ApprovalDecision> decisions) {
        public StageEvidence {
            approvers = List.copyOf(approvers);
            decisions = List.copyOf(decisions);
        }
    }

    public record CaseEvidence(
            ApprovalCase approvalCase,
            ApprovalPlan plan,
            List<StageEvidence> stages) {
        public CaseEvidence {
            stages = List.copyOf(stages);
        }
    }
}
