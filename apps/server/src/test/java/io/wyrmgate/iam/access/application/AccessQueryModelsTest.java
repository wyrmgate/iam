package io.wyrmgate.iam.access.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.wyrmgate.iam.access.application.AccessQueryModels.AssignmentFilter;
import io.wyrmgate.iam.access.application.AccessQueryModels.AssignmentValidity;
import io.wyrmgate.iam.access.application.AccessQueryModels.EffectiveSupportPosition;
import io.wyrmgate.iam.access.domain.AccessAssignment;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccessQueryModelsTest {

    @Test
    void assignmentFilterBindingIncludesEverySemanticFilter() {
        UUID identityId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        AssignmentFilter filter = new AssignmentFilter(
                identityId,
                AccessAssignment.LifecycleState.ACTIVE,
                AccessAssignment.TargetKind.ROLE,
                targetId,
                AccessAssignment.ProvenanceKind.REQUEST_ITEM,
                AssignmentValidity.CURRENT);

        assertThat(filter.cursorBinding())
                .isEqualTo(identityId + "|ACTIVE|ROLE|" + targetId + "|REQUEST_ITEM|CURRENT");
        assertThat(filter.cursorBinding()).isNotEqualTo(AssignmentFilter.none().cursorBinding());
    }

    @Test
    void targetIdRequiresTargetKind() {
        assertThatThrownBy(() -> new AssignmentFilter(
                null, null, null, UUID.randomUUID(), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("targetId requires targetKind");
    }

    @Test
    void supportPositionRequiresDeterministicCompositePosition() {
        UUID assignmentId = UUID.randomUUID();
        assertThat(new EffectiveSupportPosition(assignmentId, "abc123").accessAssignmentId())
                .isEqualTo(assignmentId);
        assertThatThrownBy(() -> new EffectiveSupportPosition(assignmentId, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
