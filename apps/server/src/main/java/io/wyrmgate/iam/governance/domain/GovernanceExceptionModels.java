package io.wyrmgate.iam.governance.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class GovernanceExceptionModels {
    private GovernanceExceptionModels() {}

    public enum ScopeKind {
        IDENTITY_SOD_RULE
    }

    public enum LifecycleState {
        PENDING_APPROVAL,
        APPROVED,
        REJECTED,
        REVOKED,
        EXPIRED
    }

    public record GovernanceException(
            UUID id,
            ScopeKind scopeKind,
            UUID subjectIdentityId,
            UUID sodRuleId,
            UUID requesterIdentityId,
            String businessReason,
            Instant validFrom,
            Instant validUntil,
            LifecycleState lifecycleState,
            UUID approvalCaseId,
            UUID predecessorExceptionId,
            long revision,
            Instant createdAt,
            Instant updatedAt,
            Instant approvedAt,
            Instant rejectedAt,
            Instant revokedAt,
            Instant expiredAt) {
        public GovernanceException {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(scopeKind, "scopeKind");
            Objects.requireNonNull(subjectIdentityId, "subjectIdentityId");
            Objects.requireNonNull(sodRuleId, "sodRuleId");
            Objects.requireNonNull(requesterIdentityId, "requesterIdentityId");
            if (businessReason == null || businessReason.isBlank()) {
                throw new IllegalArgumentException("businessReason is required");
            }
            businessReason = businessReason.trim();
            if (businessReason.length() > 1000) {
                throw new IllegalArgumentException("businessReason exceeds 1000 characters");
            }
            Objects.requireNonNull(validFrom, "validFrom");
            Objects.requireNonNull(validUntil, "validUntil");
            if (!validUntil.isAfter(validFrom)) {
                throw new IllegalArgumentException("validUntil must be after validFrom");
            }
            Objects.requireNonNull(lifecycleState, "lifecycleState");
            Objects.requireNonNull(approvalCaseId, "approvalCaseId");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
            if (revision < 1) {
                throw new IllegalArgumentException("revision must be positive");
            }
        }

        public boolean effectiveAt(Instant at) {
            Objects.requireNonNull(at, "at");
            return lifecycleState == LifecycleState.APPROVED
                    && !at.isBefore(validFrom)
                    && at.isBefore(validUntil);
        }
    }
}
