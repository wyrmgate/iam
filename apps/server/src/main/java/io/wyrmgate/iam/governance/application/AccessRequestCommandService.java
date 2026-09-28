package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.application.AccessRequestModels.AccessRequest;
import io.wyrmgate.iam.governance.application.AccessRequestModels.EligibilityOutcome;
import io.wyrmgate.iam.governance.application.AccessRequestModels.EligibilityResult;
import io.wyrmgate.iam.governance.application.AccessRequestModels.ItemSpec;
import io.wyrmgate.iam.governance.application.AccessRequestModels.ItemState;
import io.wyrmgate.iam.governance.application.AccessRequestModels.RequestDetail;
import io.wyrmgate.iam.governance.application.AccessRequestModels.RequestItem;
import io.wyrmgate.iam.governance.application.AccessRequestModels.RequestState;
import io.wyrmgate.iam.governance.application.AccessRequestModels.TargetKind;
import io.wyrmgate.iam.governance.application.ApprovalModels.ApprovalCase;
import io.wyrmgate.iam.governance.application.ApprovalModels.SubjectKind;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class AccessRequestCommandService {

    private final AccessRequestRepository repository;
    private final AccessRequestEligibilityEvaluator evaluator;
    private final ApprovalCommandService approvals;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;

    public AccessRequestCommandService(
            AccessRequestRepository repository,
            AccessRequestEligibilityEvaluator evaluator,
            ApprovalCommandService approvals,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public RequestDetail createDraft(
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
        if (itemSpecs.isEmpty()) {
            throw new IllegalArgumentException(
                    "AccessRequest requires at least one RequestItem");
        }
        if (itemSpecs.size() > 1000) {
            throw new IllegalArgumentException(
                    "AccessRequest supports at most 1000 items in one request");
        }

        return transactions.required(() -> {
            UUID requestId = ids.nextId();
            AccessRequest request = new AccessRequest(
                    requestId,
                    requesterIdentityId,
                    beneficiaryIdentityId,
                    RequestState.DRAFT,
                    1,
                    now,
                    null,
                    now);
            repository.insertRequest(tenant, request);

            for (ItemSpec spec : itemSpecs) {
                RequestItem item = new RequestItem(
                        ids.nextId(),
                        requestId,
                        spec.targetKind(),
                        spec.targetKind() == TargetKind.ROLE
                                ? spec.targetId()
                                : null,
                        spec.targetKind() == TargetKind.ENTITLEMENT
                                ? spec.targetId()
                                : null,
                        ItemState.DRAFT,
                        null,
                        null,
                        1,
                        now,
                        now);
                repository.insertItem(tenant, item);
            }
            return detail(tenant, requestId);
        });
    }

    public RequestDetail submit(
            TenantContext tenant,
            UUID requestId,
            long expectedRevision,
            Instant now) {
        Objects.requireNonNull(now, "now");
        return transactions.required(() -> {
            AccessRequest request = requireRequest(tenant, requestId);
            if (request.revision() != expectedRevision) {
                throw new StaleWriteException(
                        "access-request", requestId, expectedRevision);
            }
            if (request.state() != RequestState.DRAFT) {
                throw new AccessRequestCommandException(
                        "access_request_not_draft",
                        "Only a DRAFT AccessRequest can be submitted.");
            }
            List<RequestItem> items =
                    repository.findItems(tenant, requestId);
            if (items.isEmpty()) {
                throw new AccessRequestCommandException(
                        "access_request_empty",
                        "An AccessRequest must contain at least one RequestItem.");
            }
            for (RequestItem item : items) {
                if (item.state() != ItemState.DRAFT) {
                    throw new AccessRequestCommandException(
                            "request_item_not_draft",
                            "All RequestItems must be DRAFT at submission.");
                }
                repository.updateItemState(
                        tenant,
                        item.id(),
                        ItemState.SUBMITTED,
                        null,
                        null,
                        item.revision(),
                        now);
            }
            repository.updateRequestState(
                    tenant,
                    requestId,
                    RequestState.SUBMITTED,
                    expectedRevision,
                    now,
                    now);
            return detail(tenant, requestId);
        });
    }

    public RequestItem evaluateItem(
            TenantContext tenant,
            UUID itemId,
            long expectedRevision,
            Instant now) {
        Objects.requireNonNull(now, "now");
        return transactions.required(() -> {
            RequestItem current = requireItem(tenant, itemId);
            if (current.revision() != expectedRevision) {
                throw new StaleWriteException(
                        "request-item", itemId, expectedRevision);
            }
            if (current.state() != ItemState.SUBMITTED
                    && current.state() != ItemState.EVALUATING) {
                throw new AccessRequestCommandException(
                        "request_item_not_evaluable",
                        "Only SUBMITTED or EVALUATING RequestItem can be evaluated.");
            }

            RequestItem evaluating = current;
            if (current.state() == ItemState.SUBMITTED) {
                evaluating = repository.updateItemState(
                        tenant,
                        itemId,
                        ItemState.EVALUATING,
                        null,
                        null,
                        current.revision(),
                        now);
            }

            AccessRequest request = requireRequest(
                    tenant, evaluating.accessRequestId());
            EligibilityResult result;
            try {
                result = Objects.requireNonNull(
                        evaluator.evaluate(
                                tenant, request, evaluating),
                        "eligibility evaluator result");
            } catch (RuntimeException unavailable) {
                result = EligibilityResult.unavailable(
                        "mandatory_evaluator_unavailable");
            }

            if (result.outcome() == EligibilityOutcome.UNAVAILABLE) {
                return repository.updateItemState(
                        tenant,
                        itemId,
                        ItemState.EVALUATING,
                        null,
                        normalizeCode(result.code()),
                        evaluating.revision(),
                        now);
            }
            if (result.outcome() == EligibilityOutcome.DENIED) {
                return repository.updateItemState(
                        tenant,
                        itemId,
                        ItemState.DENIED,
                        null,
                        normalizeCode(result.code()),
                        evaluating.revision(),
                        now);
            }
            if (result.outcome() == EligibilityOutcome.AUTHORIZED) {
                return repository.updateItemState(
                        tenant,
                        itemId,
                        ItemState.AUTHORIZED,
                        null,
                        normalizeCode(result.code()),
                        evaluating.revision(),
                        now);
            }

            ApprovalCase approvalCase = approvals.start(
                    tenant,
                    SubjectKind.ACCESS_REQUEST_ITEM,
                    itemId,
                    request.requesterIdentityId(),
                    result.approvalPlan(),
                    now);
            return repository.updateItemState(
                    tenant,
                    itemId,
                    ItemState.PENDING_APPROVAL,
                    approvalCase.id(),
                    normalizeCode(result.code()),
                    evaluating.revision(),
                    now);
        });
    }

    public RequestDetail find(
            TenantContext tenant,
            UUID requestId) {
        return detail(tenant, requestId);
    }

    private RequestDetail detail(
            TenantContext tenant, UUID requestId) {
        AccessRequest request = requireRequest(tenant, requestId);
        return new RequestDetail(
                request,
                repository.findItems(tenant, requestId));
    }

    private AccessRequest requireRequest(
            TenantContext tenant, UUID requestId) {
        return repository.findRequest(tenant, requestId)
                .orElseThrow(() -> new AccessRequestCommandException(
                        "access_request_not_found",
                        "The requested AccessRequest was not found."));
    }

    private RequestItem requireItem(
            TenantContext tenant, UUID itemId) {
        return repository.findItem(tenant, itemId)
                .orElseThrow(() -> new AccessRequestCommandException(
                        "request_item_not_found",
                        "The requested RequestItem was not found."));
    }

    private static String normalizeCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalized = code.trim();
        if (normalized.length() > 128) {
            return normalized.substring(0, 128);
        }
        return normalized;
    }
}
