package io.wyrmgate.iam.audit.application;

import io.wyrmgate.iam.audit.domain.AuditOutcome;
import io.wyrmgate.iam.audit.domain.AuditRecord;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class AuditQueryModels {
    private AuditQueryModels() {
    }

    public record AuditPagePosition(Instant occurredAt, UUID id) {
        public AuditPagePosition {
            Objects.requireNonNull(occurredAt, "occurredAt");
            Objects.requireNonNull(id, "id");
        }
    }

    public record AuditFilter(
            UUID actorId,
            String actionType,
            String resourceType,
            UUID resourceId,
            AuditOutcome outcome,
            UUID correlationId) {
        public AuditFilter {
            if (actionType != null) {
                if (actionType.isBlank()) throw new IllegalArgumentException("actionType must not be blank");
                actionType = boundedType(actionType, "actionType");
            }
            if (resourceType != null) {
                if (resourceType.isBlank()) throw new IllegalArgumentException("resourceType must not be blank");
                resourceType = boundedType(resourceType, "resourceType");
            }
        }

        private static String boundedType(String value, String field) {
            String normalized = value.trim();
            if (normalized.length() > 128) {
                throw new IllegalArgumentException(field + " must not exceed 128 characters");
            }
            return normalized;
        }

        public static AuditFilter none() {
            return new AuditFilter(null, null, null, null, null, null);
        }
    }

    public record AuditPage(List<AuditRecord> items, AuditPagePosition nextPosition) {
        public AuditPage {
            items = List.copyOf(Objects.requireNonNull(items, "items"));
        }
    }
}
