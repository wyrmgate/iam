package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.application.AccessRequestModels.*;
import io.wyrmgate.iam.governance.application.ApprovalModels.*;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;

/** Governance-owned decision transition; caller owns the local RequestItem transaction. */
public final class RequestAdmissionDecisionService {
    private final AccessRequestRepository requests;
    private final ApprovalRepository approvals;
    private final ApprovalCaseStartService starter;
    private final AuthorizedAccessIntentSink authorizedAccess;

    public RequestAdmissionDecisionService(AccessRequestRepository requests, ApprovalRepository approvals,
            ApprovalCaseStartService starter, AuthorizedAccessIntentSink authorizedAccess) {
        this.requests = Objects.requireNonNull(requests);
        this.approvals = Objects.requireNonNull(approvals);
        this.starter = Objects.requireNonNull(starter);
        this.authorizedAccess = Objects.requireNonNull(authorizedAccess);
    }

    public static EligibilityResult evaluate(AccessRequestEligibilityEvaluator evaluator,
            TenantContext tenant, AccessRequest request, RequestItem item) {
        try {
            return Objects.requireNonNull(evaluator.evaluate(tenant, request, item));
        } catch (RuntimeException unavailable) {
            return EligibilityResult.unavailable("mandatory_evaluator_unavailable");
        }
    }

    public boolean approvalSatisfied(TenantContext tenant, RequestItem item, EligibilityResult result) {
        if (result.outcome() == EligibilityOutcome.AUTHORIZED) return true;
        if (result.outcome() != EligibilityOutcome.APPROVAL_REQUIRED || item.approvalCaseId() == null)
            return false;
        var approval = approvals.findCase(tenant, item.approvalCaseId()).orElse(null);
        if (approval == null || approval.state() != CaseState.APPROVED
                || approval.subjectKind() != SubjectKind.ACCESS_REQUEST_ITEM
                || !approval.subjectId().equals(item.id())) return false;
        var original = requests.findApprovalContext(tenant, item.id(), approval.id()).orElse(null);
        return result.context().sameMaterial(original)
                && approvals.findPlan(tenant, approval.id()).contentHash()
                        .equals(ApprovalCaseStartService.contentHash(result.approvalPlan()));
    }

    public RequestItem finish(TenantContext tenant, AccessRequest request, RequestItem item,
            EligibilityResult result, Instant now) {
        if (result.outcome() == EligibilityOutcome.UNAVAILABLE) {
            return update(tenant, item, ItemState.EVALUATING, item.approvalCaseId(), result.code(), now);
        }
        if (item.validUntil() != null && !item.validUntil().isAfter(now)) {
            return update(tenant, item, ItemState.EXPIRED, item.approvalCaseId(), "validity_already_ended", now);
        }
        var previous = item.state() == ItemState.AUTHORIZED
                ? requests.findAdmissionContext(tenant, item.id(), item.revision()).orElse(null)
                : item.approvalCaseId() == null ? null
                        : requests.findApprovalContext(tenant, item.id(), item.approvalCaseId()).orElse(null);
        if ((item.state() == ItemState.AUTHORIZED || item.approvalCaseId() != null) && previous == null) {
            return update(tenant, item, ItemState.CANCELLED, item.approvalCaseId(), "authorization_context_missing", now);
        }
        if (result.outcome() == EligibilityOutcome.DENIED) {
            ItemState state = "identity_not_access_eligible".equals(result.code())
                    ? ItemState.CANCELLED : ItemState.DENIED;
            return update(tenant, item, state, item.approvalCaseId(), result.code(), now);
        }
        if (previous != null && previous.identityEpoch() != result.context().identityEpoch()) {
            return update(tenant, item, ItemState.CANCELLED, item.approvalCaseId(), "authorization_identity_epoch_stale", now);
        }
        if (approvalSatisfied(tenant, item, result)) {
            if (item.state() == ItemState.AUTHORIZED && result.context().sameMaterial(previous)) return item;
            var authorized = update(tenant, item, ItemState.AUTHORIZED, item.approvalCaseId(), result.code(), now);
            requests.recordAdmissionContext(tenant, authorized, result.context());
            authorizedAccess.authorized(tenant, authorized);
            return authorized;
        }
        var replacement = starter.start(tenant, SubjectKind.ACCESS_REQUEST_ITEM, item.id(),
                request.requesterIdentityId(), result.approvalPlan(), now);
        var pending = update(tenant, item, ItemState.PENDING_APPROVAL, replacement.id(), result.code(), now);
        requests.recordAdmissionContext(tenant, pending, result.context());
        return pending;
    }

    private RequestItem update(TenantContext tenant, RequestItem item, ItemState state,
            java.util.UUID approvalCaseId, String code, Instant now) {
        String normalized = code == null || code.isBlank() ? null : code.trim();
        if (normalized != null && normalized.length() > 128) normalized = normalized.substring(0, 128);
        return requests.updateItemState(tenant, item.id(), state, approvalCaseId, null,
                normalized, item.revision(), now);
    }
}
