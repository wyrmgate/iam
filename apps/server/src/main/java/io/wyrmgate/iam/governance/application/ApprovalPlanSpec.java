package io.wyrmgate.iam.governance.application;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ApprovalPlanSpec(
        SelfApprovalPolicy selfApprovalPolicy,
        List<StageSpec> stages) {

    public ApprovalPlanSpec {
        Objects.requireNonNull(selfApprovalPolicy, "selfApprovalPolicy");
        stages = List.copyOf(stages);
        if (stages.isEmpty() || stages.size() > 20) {
            throw new IllegalArgumentException(
                    "approval plan must contain between 1 and 20 stages");
        }
    }

    public record StageSpec(
            DecisionMode decisionMode,
            List<UUID> participantIdentityIds) {
        public StageSpec {
            Objects.requireNonNull(decisionMode, "decisionMode");
            participantIdentityIds = List.copyOf(participantIdentityIds);
            if (participantIdentityIds.isEmpty()
                    || participantIdentityIds.size() > 100) {
                throw new IllegalArgumentException(
                        "approval stage must contain between 1 and 100 participants");
            }
            if (participantIdentityIds.stream().anyMatch(Objects::isNull)
                    || new HashSet<>(participantIdentityIds).size()
                            != participantIdentityIds.size()) {
                throw new IllegalArgumentException(
                        "approval stage participants must be unique non-null identities");
            }
        }
    }

    public enum SelfApprovalPolicy {
        DENY_REQUESTER,
        ALLOW_REQUESTER
    }

    public enum DecisionMode {
        ANY_ONE,
        ALL
    }
}
