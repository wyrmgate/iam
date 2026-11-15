package io.wyrmgate.iam.governance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.wyrmgate.iam.governance.application.ApprovalModels.DecisionMode;
import io.wyrmgate.iam.governance.application.ApprovalModels.PlanSpec;
import io.wyrmgate.iam.governance.application.ApprovalModels.StageSpec;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ApprovalPlanBoundsTest {
    @Test
    void typedPlansEnforceExistingPublicBoundsForEveryConsumer() {
        List<UUID> approvers = IntStream.range(0, ApprovalModels.MAX_APPROVERS_PER_STAGE + 1)
                .mapToObj(i -> new UUID(0, i + 1L)).toList();
        StageSpec stage = new StageSpec(DecisionMode.ALL,
                approvers.subList(0, ApprovalModels.MAX_APPROVERS_PER_STAGE));
        assertThat(stage.approverIdentityIds()).hasSize(100);
        assertThat(new PlanSpec(Collections.nCopies(ApprovalModels.MAX_STAGES, stage)).stages())
                .hasSize(20);
        assertThatThrownBy(() -> new StageSpec(DecisionMode.ALL, approvers))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlanSpec(Collections.nCopies(ApprovalModels.MAX_STAGES + 1, stage)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
