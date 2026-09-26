package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.access.domain.EffectiveAccess;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AccessQueryModels {
    private AccessQueryModels() {}

    public record AssignmentPosition(Instant createdAt, UUID id) {}

    public record AssignmentPage(
            List<AccessAssignment> items,
            AssignmentPosition nextPosition) {
        public AssignmentPage {
            items = List.copyOf(items);
        }
    }

    public record EffectivePosition(UUID id) {}

    public record EffectivePage(
            List<EffectiveAccess> items,
            EffectivePosition nextPosition) {
        public EffectivePage {
            items = List.copyOf(items);
        }
    }

    public record EffectiveSupport(
            UUID accessAssignmentId,
            String pathHash,
            int pathDepth,
            List<UUID> roleVersionPath) {
        public EffectiveSupport {
            roleVersionPath = List.copyOf(roleVersionPath);
        }
    }

    public record EffectiveDetail(
            EffectiveAccess effectiveAccess,
            List<EffectiveSupport> supports) {
        public EffectiveDetail {
            supports = List.copyOf(supports);
        }
    }
}
