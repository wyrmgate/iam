package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.access.application.AccessIntentCommand;
import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.governance.domain.AccessRequest;
import io.wyrmgate.iam.governance.domain.ApprovalCase;
import io.wyrmgate.iam.governance.domain.RequestItem;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQuery;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class AccessRequestService {

    private final AccessRequestRepository requests;
    private final AccessRequestEligibilityEvaluator evaluator;
    private final ApprovalService approvals;
    private final IdentityAccessReferenceQuery identities;
    private final AccessIntentCommand accessIntent;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;

    public AccessRequestService(
            AccessRequestRepository requests,
            AccessRequestEligibilityEvaluator evaluator,
            ApprovalService approvals,
            IdentityAccessReferenceQuery identities,
            AccessIntentCommand accessIntent,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this.requests = Objects.requireNonNull(requests, "requests");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.accessIntent = Objects.requireNonNull(accessIntent, "accessIntent");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(
                transactions, "transactions");
    }

    public AccessRequest createDraft(
            TenantContext tenant,
            UUID requesterIdentityId,
            UUID beneficiaryIdentityId,
            List<ItemSpec> itemSpecs,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(requesterIdentityId, "requesterIdentityId");
        Objects.requireNonNull(beneficiaryIdentityId, "beneficiaryIdentityId");
        Objects.requireNonNull(itemSpecs, "itemSpecs");
        Objects.requireNonNull(now, "now");
        if (itemSpecs.isEmpty() || itemSpecs.size() > 1000) {
            throw new IllegalArgumentException(
                    "access request must contain between 1 and 1000 items");
        }
        if (!identities.identityExists(tenant, requesterIdentityId)) {
            throw new ApprovalCommandException(
                    "requester_identity_not_found",
                    "The requester Identity was not found.");
        }
        if (!identities.identityExists(tenant, beneficiaryIdentityId)) {
            throw new ApprovalCommandException(
                    "beneficiary_identity_not_found",
                    "The beneficiary Identity was not found.");
        }

        return transactions.required(() -> {
            AccessRequest request = new AccessRequest(
                    ids.nextId(),
                    requesterIdentityId,
                    beneficiaryIdentityId,
                    AccessRequest.LifecycleState.DRAFT,
                    1,
                    now,
                    now,
                    null);
            requests.insertRequest(tenant, request);
            for (ItemSpec spec : itemSpecs) {
                requests.insertItem(
                        tenant,
                        new RequestItem(
                                ids.nextId(),
                                request.id(),
                                spec.targetKind(),
                                spec.roleId(),
                                spec.entitlementId(),
                                spec.principalConstraintKind(),
                                spec.specificPrincipalId(),
                                spec.validFrom(),
                                spec.validUntil(),
                                RequestItem.LifecycleState.DRAFT,
                                null,
                                null,
                                1,
                                now,
                                now,
                                null));
            }
            return requests.findRequest(tenant, request.id())
                    .orElseThrow();
        });
    }

    public AccessRequest submit(
            TenantContext tenant,
            UUID requestId,
            long expectedRevision,
            Instant now,
            UUID correlationId) {
        AccessRequest submitted = transactions.required(() -> {
            AccessRequest current = requests.findRequest(tenant, requestId)
                    .orElseThrow(() -> new ApprovalCommandException(
                            "access_request_not_found",
                            "The requested AccessRequest was not found."));
            if (current.revision() != expectedRevision) {
                throw new StaleWriteException(
                        "access-request", requestId, expectedRevision);
            }
            if (current.lifecycleState()
                    != AccessRequest.LifecycleState.DRAFT) {
                throw new ApprovalCommandException(
                        "access_request_not_draft",
                        "Only a DRAFT AccessRequest can be submitted.");
            }
            for (RequestItem item :
                    requests.findItems(tenant, requestId)) {
                if (item.lifecycleState()
                        != RequestItem.LifecycleState.DRAFT) {
                    throw new IllegalStateException(
                            "draft request contains non-draft item");
                }
                requests.updateItemState(
                        tenant,
                        item.id(),
                        item.revision(),
                        RequestItem.LifecycleState.SUBMITTED,
                        null,
                        null,
                        now,
                        null);
            }
            return requests.updateRequestState(
                    tenant,
                    requestId,
                    expectedRevision,
                    AccessRequest.LifecycleState.SUBMITTED,
                    now,
                    null);
        });

        for (RequestItem item : requests.findItems(tenant, requestId)) {
            evaluateItem(
                    tenant,
                    item.id(),
                    now,
                    correlationId,
                    requestId);
        }
        return submitted;
    }

    public RequestItem evaluateItem(
            TenantContext tenant,
            UUID requestItemId,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        RequestItem evaluating = transactions.required(() -> {
            RequestItem current = requests.findItem(
                            tenant, requestItemId)
                    .orElseThrow(() -> new ApprovalCommandException(
                            "request_item_not_found",
                            "The requested RequestItem was not found."));
            if (current.lifecycleState()
                    == RequestItem.LifecycleState.SUBMITTED) {
                return requests.updateItemState(
                        tenant,
                        current.id(),
                        current.revision(),
                        RequestItem.LifecycleState.EVALUATING,
                        current.approvalCaseId(),
                        null,
                        now,
                        null);
            }
            if (current.lifecycleState()
                    == RequestItem.LifecycleState.EVALUATING) {
                return current;
            }
            throw new ApprovalCommandException(
                    "request_item_not_evaluatable",
                    "Only SUBMITTED or EVALUATING RequestItem can be evaluated.");
        });

        AccessRequest request = requests.findRequest(
                        tenant, evaluating.accessRequestId())
                .orElseThrow();
        AccessRequestEligibilityEvaluator.Evaluation result =
                evaluator.evaluate(tenant, request, evaluating);

        if (result.status()
                == AccessRequestEligibilityEvaluator.Status.UNAVAILABLE) {
            return evaluating;
        }

        return transactions.required(() -> {
            RequestItem current = requests.findItem(
                            tenant, requestItemId)
                    .orElseThrow();
            if (current.lifecycleState()
                    != RequestItem.LifecycleState.EVALUATING
                    || current.revision() != evaluating.revision()) {
                throw new StaleWriteException(
                        "request-item",
                        requestItemId,
                        evaluating.revision());
            }

            if (result.status()
                    == AccessRequestEligibilityEvaluator.Status.DENIED) {
                RequestItem denied = requests.updateItemState(
                        tenant,
                        current.id(),
                        current.revision(),
                        RequestItem.LifecycleState.DENIED,
                        null,
                        null,
                        now,
                        now);
                completeRequestIfDone(
                        tenant, request, now);
                return denied;
            }

            long plannedSubjectRevision = current.revision() + 1;
            ApprovalCase approvalCase = approvals.openCase(
                    tenant,
                    ApprovalCase.SubjectType.REQUEST_ITEM,
                    current.id(),
                    plannedSubjectRevision,
                    request.requesterIdentityId(),
                    result.approvalPlan(),
                    now,
                    correlationId,
                    causationId);
            return requests.updateItemState(
                    tenant,
                    current.id(),
                    current.revision(),
                    RequestItem.LifecycleState.PENDING_APPROVAL,
                    approvalCase.id(),
                    null,
                    now,
                    null);
        });
    }

    public RequestItem applyApprovalOutcome(
            TenantContext tenant,
            UUID approvalCaseId,
            UUID requestItemId,
            long capturedSubjectRevision,
            ApprovalCase.LifecycleState outcome,
            Instant now) {
        RequestItem afterOutcome = transactions.required(() -> {
            RequestItem current = requests.findItem(
                            tenant, requestItemId)
                    .orElseThrow(() -> new ApprovalCommandException(
                            "request_item_not_found",
                            "The approval subject RequestItem was not found."));
            if (!approvalCaseId.equals(current.approvalCaseId())) {
                return current;
            }
            if (current.lifecycleState()
                    == RequestItem.LifecycleState.APPLIED
                    || current.lifecycleState()
                            == RequestItem.LifecycleState.REJECTED) {
                return current;
            }
            if (current.lifecycleState()
                    == RequestItem.LifecycleState.AUTHORIZED
                    && outcome == ApprovalCase.LifecycleState.APPROVED) {
                return current;
            }
            if (current.lifecycleState()
                            != RequestItem.LifecycleState.PENDING_APPROVAL
                    || current.revision() != capturedSubjectRevision) {
                return current;
            }
            if (outcome == ApprovalCase.LifecycleState.REJECTED) {
                return requests.updateItemState(
                        tenant,
                        current.id(),
                        current.revision(),
                        RequestItem.LifecycleState.REJECTED,
                        current.approvalCaseId(),
                        null,
                        now,
                        now);
            }
            if (outcome != ApprovalCase.LifecycleState.APPROVED) {
                return current;
            }
            return requests.updateItemState(
                    tenant,
                    current.id(),
                    current.revision(),
                    RequestItem.LifecycleState.AUTHORIZED,
                    current.approvalCaseId(),
                    null,
                    now,
                    null);
        });

        if (afterOutcome.lifecycleState()
                != RequestItem.LifecycleState.AUTHORIZED) {
            AccessRequest request = requests.findRequest(
                            tenant, afterOutcome.accessRequestId())
                    .orElseThrow();
            completeRequestIfDone(tenant, request, now);
            return afterOutcome;
        }

        AccessRequest request = requests.findRequest(
                        tenant, afterOutcome.accessRequestId())
                .orElseThrow();
        AccessAssignment assignment = accessIntent.applyApprovedRequest(
                tenant,
                request.beneficiaryIdentityId(),
                map(afterOutcome.targetKind()),
                afterOutcome.roleId(),
                afterOutcome.entitlementId(),
                map(afterOutcome.principalConstraintKind()),
                afterOutcome.specificPrincipalId(),
                afterOutcome.id(),
                afterOutcome.validFrom(),
                afterOutcome.validUntil(),
                now);

        RequestItem applied = transactions.required(() -> {
            RequestItem current = requests.findItem(
                            tenant, afterOutcome.id())
                    .orElseThrow();
            if (current.lifecycleState()
                    == RequestItem.LifecycleState.APPLIED) {
                return current;
            }
            if (current.lifecycleState()
                    != RequestItem.LifecycleState.AUTHORIZED) {
                return current;
            }
            return requests.updateItemState(
                    tenant,
                    current.id(),
                    current.revision(),
                    RequestItem.LifecycleState.APPLIED,
                    current.approvalCaseId(),
                    assignment.id(),
                    now,
                    now);
        });
        completeRequestIfDone(tenant, request, now);
        return applied;
    }

    private void completeRequestIfDone(
            TenantContext tenant,
            AccessRequest request,
            Instant now) {
        if (request.lifecycleState()
                != AccessRequest.LifecycleState.SUBMITTED) {
            return;
        }
        boolean allDone = requests.findItems(tenant, request.id())
                .stream()
                .allMatch(item -> switch (item.lifecycleState()) {
                    case APPLIED, DENIED, REJECTED,
                            CANCELLED, EXPIRED -> true;
                    default -> false;
                });
        if (!allDone) return;
        AccessRequest current = requests.findRequest(
                        tenant, request.id())
                .orElseThrow();
        if (current.lifecycleState()
                == AccessRequest.LifecycleState.SUBMITTED) {
            requests.updateRequestState(
                    tenant,
                    current.id(),
                    current.revision(),
                    AccessRequest.LifecycleState.COMPLETED,
                    now,
                    now);
        }
    }

    private static AccessAssignment.TargetKind map(
            RequestItem.TargetKind value) {
        return AccessAssignment.TargetKind.valueOf(value.name());
    }

    private static AccessAssignment.PrincipalConstraintKind map(
            RequestItem.PrincipalConstraintKind value) {
        return AccessAssignment.PrincipalConstraintKind.valueOf(
                value.name());
    }

    public record ItemSpec(
            RequestItem.TargetKind targetKind,
            UUID roleId,
            UUID entitlementId,
            RequestItem.PrincipalConstraintKind principalConstraintKind,
            UUID specificPrincipalId,
            Instant validFrom,
            Instant validUntil) {

        public ItemSpec {
            Objects.requireNonNull(targetKind, "targetKind");
            Objects.requireNonNull(
                    principalConstraintKind,
                    "principalConstraintKind");
            if (targetKind == RequestItem.TargetKind.ROLE) {
                Objects.requireNonNull(roleId, "roleId");
                if (entitlementId != null) {
                    throw new IllegalArgumentException(
                            "ROLE item must not carry entitlementId");
                }
            } else {
                Objects.requireNonNull(entitlementId, "entitlementId");
                if (roleId != null) {
                    throw new IllegalArgumentException(
                            "ENTITLEMENT item must not carry roleId");
                }
            }
            if (principalConstraintKind
                    == RequestItem.PrincipalConstraintKind.SPECIFIC) {
                Objects.requireNonNull(
                        specificPrincipalId,
                        "specificPrincipalId");
            } else if (specificPrincipalId != null) {
                throw new IllegalArgumentException(
                        "ANY item must not carry specificPrincipalId");
            }
            if (validFrom != null && validUntil != null
                    && !validUntil.isAfter(validFrom)) {
                throw new IllegalArgumentException(
                        "validUntil must be after validFrom");
            }
        }
    }
}
