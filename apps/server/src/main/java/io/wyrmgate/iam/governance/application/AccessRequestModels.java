package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.application.ApprovalModels.PlanSpec;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class AccessRequestModels {
    private AccessRequestModels() {}

    public enum RequestState {
        DRAFT,
        SUBMITTED,
        IN_PROGRESS,
        COMPLETED,
        CANCELLED,
        EXPIRED
    }

    public enum ItemState {
        DRAFT,
        SUBMITTED,
        EVALUATING,
        PENDING_APPROVAL,
        AUTHORIZED,
        APPLIED,
        DENIED,
        REJECTED,
        CANCELLED,
        EXPIRED
    }

    public enum TargetKind {
        ROLE,
        ENTITLEMENT
    }

    public record AccessRequest(
            UUID id,
            UUID requesterIdentityId,
            UUID beneficiaryIdentityId,
            RequestState state,
            long revision,
            Instant createdAt,
            Instant submittedAt,
            Instant updatedAt) {}

    public record RequestItem(
            UUID id,
            UUID accessRequestId,
            TargetKind targetKind,
            UUID roleId,
            UUID entitlementId,
            ItemState state,
            UUID approvalCaseId,
            String evaluationCode,
            long revision,
            Instant createdAt,
            Instant updatedAt) {
        public RequestItem {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(accessRequestId, "accessRequestId");
            Objects.requireNonNull(targetKind, "targetKind");
            Objects.requireNonNull(state, "state");
            if (targetKind == TargetKind.ROLE) {
                Objects.requireNonNull(roleId, "roleId");
                if (entitlementId != null) {
                    throw new IllegalArgumentException(
                            "ROLE request item must not carry entitlementId");
                }
            } else {
                Objects.requireNonNull(entitlementId, "entitlementId");
                if (roleId != null) {
                    throw new IllegalArgumentException(
                            "ENTITLEMENT request item must not carry roleId");
                }
            }
            if (revision < 1) {
                throw new IllegalArgumentException("revision must be positive");
            }
        }
    }

    public record ItemSpec(
            TargetKind targetKind,
            UUID targetId) {
        public ItemSpec {
            Objects.requireNonNull(targetKind, "targetKind");
            Objects.requireNonNull(targetId, "targetId");
        }
    }

    public enum EligibilityOutcome {
        AUTHORIZED,
        APPROVAL_REQUIRED,
        DENIED,
        UNAVAILABLE
    }

    public record EligibilityResult(
            EligibilityOutcome outcome,
            String code,
            PlanSpec approvalPlan) {
        public EligibilityResult {
            Objects.requireNonNull(outcome, "outcome");
            if (outcome == EligibilityOutcome.APPROVAL_REQUIRED
                    && approvalPlan == null) {
                throw new IllegalArgumentException(
                        "APPROVAL_REQUIRED needs an approval plan");
            }
            if (outcome != EligibilityOutcome.APPROVAL_REQUIRED
                    && approvalPlan != null) {
                throw new IllegalArgumentException(
                        "approval plan is only valid for APPROVAL_REQUIRED");
            }
        }

        public static EligibilityResult authorized() {
            return new EligibilityResult(
                    EligibilityOutcome.AUTHORIZED, "eligible", null);
        }

        public static EligibilityResult approvalRequired(
                PlanSpec plan) {
            return new EligibilityResult(
                    EligibilityOutcome.APPROVAL_REQUIRED,
                    "approval_required",
                    plan);
        }

        public static EligibilityResult denied(String code) {
            return new EligibilityResult(
                    EligibilityOutcome.DENIED, code, null);
        }

        public static EligibilityResult unavailable(String code) {
            return new EligibilityResult(
                    EligibilityOutcome.UNAVAILABLE, code, null);
        }
    }

    public record RequestDetail(
            AccessRequest request,
            List<RequestItem> items) {
        public RequestDetail {
            items = List.copyOf(items);
        }
    }
}
