package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.access.domain.EffectiveAccess;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AccessQueryModels {
    private AccessQueryModels() {}

    public enum AssignmentValidity {
        CURRENT,
        FUTURE,
        ENDED
    }

    public record AssignmentFilter(
            UUID identityId,
            AccessAssignment.LifecycleState lifecycleState,
            AccessAssignment.TargetKind targetKind,
            UUID targetId,
            AccessAssignment.ProvenanceKind provenanceKind,
            AssignmentValidity validity) {

        public AssignmentFilter {
            if (targetId != null && targetKind == null) {
                throw new IllegalArgumentException("targetId requires targetKind");
            }
        }

        public static AssignmentFilter none() {
            return new AssignmentFilter(null, null, null, null, null, null);
        }

        public boolean empty() {
            return identityId == null
                    && lifecycleState == null
                    && targetKind == null
                    && targetId == null
                    && provenanceKind == null
                    && validity == null;
        }

        public String cursorBinding() {
            return String.join("|",
                    identityId == null ? "" : identityId.toString(),
                    lifecycleState == null ? "" : lifecycleState.name(),
                    targetKind == null ? "" : targetKind.name(),
                    targetId == null ? "" : targetId.toString(),
                    provenanceKind == null ? "" : provenanceKind.name(),
                    validity == null ? "" : validity.name());
        }
    }

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

    public record EffectiveSupportPosition(UUID accessAssignmentId, String pathHash) {
        public EffectiveSupportPosition {
            if (accessAssignmentId == null) {
                throw new IllegalArgumentException("accessAssignmentId is required");
            }
            if (pathHash == null || pathHash.isBlank()) {
                throw new IllegalArgumentException("pathHash is required");
            }
        }
    }

    public record EffectiveSupportPage(
            List<EffectiveSupport> items,
            EffectiveSupportPosition nextPosition) {
        public EffectiveSupportPage {
            items = List.copyOf(items);
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
