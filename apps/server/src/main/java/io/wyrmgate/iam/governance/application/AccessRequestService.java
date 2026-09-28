package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.access.application.AccessIntentCommand;
import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.governance.domain.AccessRequest;
import io.wyrmgate.iam.governance.domain.ApprovalSubject;
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
    private final ApprovalRepository approvalRepository;
    private final ApprovalService approvals;
    private final AccessRequestEligibilityEvaluator eligibility;
    private final AccessRequestApprovalRequirementsResolver approvalRequirements;
    private final IdentityAccessReferenceQuery identities;
    private final AccessIntentCommand access;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;

    public AccessRequestService(
            AccessRequestRepository requests,
            ApprovalRepository approvalRepository,
            ApprovalService approvals,
            AccessRequestEligibilityEvaluator eligibility,
            AccessRequestApprovalRequirementsResolver approvalRequirements,
            IdentityAccessReferenceQuery identities,
            AccessIntentCommand access,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this.requests = Objects.requireNonNull(requests, "requests");
        this.approvalRepository = Objects.requireNonNull(
                approvalRepository, "approvalRepository");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.eligibility = Objects.requireNonNull(eligibility, "eligibility");
        this.approvalRequirements = Objects.requireNonNull(
                approvalRequirements, "approvalRequirements");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.access = Objects.requireNonNull(access, "access");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public AccessRequest create(
            TenantContext tenant,
            UUID requesterIdentityId,
            UUID beneficiaryIdentityId,
            List<ItemSpec> items,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(requesterIdentityId, "requesterIdentityId");
        Objects.requireNonNull(beneficiaryIdentityId, "beneficiaryIdentityId");
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(now, "now");
        if (items.isEmpty()) {
            throw new AccessRequestCommandException(
                    "access_request_empty",
                    "AccessRequest requires at least one RequestItem.");
        }
        if (!identities.identityExists(tenant, requesterIdentityId)) {
            throw new AccessRequestCommandException(
                    "requester_not_found",
                    "Requester Identity was not found.");
        }
        if (!identities.identityExists(tenant, beneficiaryIdentityId)) {
            throw new AccessRequestCommandException(
                    "beneficiary_not_found",
                    "Beneficiary Identity was not found.");
        }

        UUID requestId = ids.nextId();
        AccessRequest request = new AccessRequest(
                requestId,
                requesterIdentityId,
                beneficiaryIdentityId,
                AccessRequest.LifecycleState.DRAFT,
                1,
                now,
                now,
                null,
                null);
        List<RequestItem> createdItems = items.stream()
                .map(spec -> spec.toItem(ids.nextId(), requestId, now))
                .toList();

        return transactions.required(() -> {
            requests.insertRequest(tenant, request);
            createdItems.forEach(item ->
                    requests.insertItem(tenant, item));
            return requests.findRequest(tenant, requestId).orElseThrow();
        });
    }

    public AccessRequest submit(
            TenantContext tenant,
            UUID requestId,
            long expectedRevision,
            Instant now) {
        AccessRequest submitted = transactions.required(() -> {
            AccessRequest current = requests.findRequest(tenant, requestId)
                    .orElseThrow(() -> new AccessRequestCommandException(
                            "access_request_not_found",
                            "AccessRequest was not found."));
            if (current.revision() != expectedRevision) {
                throw new StaleWriteException(
                        "access-request", requestId, expectedRevision);
            }
            if (current.lifecycleState()
                    != AccessRequest.LifecycleState.DRAFT) {
                throw new AccessRequestCommandException(
                        "access_request_not_draft",
                        "Only a DRAFT AccessRequest can be submitted.");
            }
            List<RequestItem> items =
                    requests.findItems(tenant, requestId);
            if (items.isEmpty()) {
                throw new AccessRequestCommandException(
                        "access_request_empty",
                        "AccessRequest requires at least one RequestItem.");
            }
            AccessRequest state = requests.updateRequestState(
                    tenant,
                    requestId,
                    AccessRequest.LifecycleState.SUBMITTED,
                    expectedRevision,
                    now,
                    now,
                    null);
            for (RequestItem item : items) {
                requests.updateItemState(
                        tenant,
                        item.id(),
                        RequestItem.LifecycleState.SUBMITTED,
                        null,
                        null,
                        null,
                        item.revision(),
                        now);
            }
            return requests.updateRequestState(
                    tenant,
                    requestId,
                    AccessRequest.LifecycleState.IN_PROGRESS,
                    state.revision(),
                    now,
                    null,
                    null);
        });

        for (RequestItem item : requests.findItems(tenant, requestId)) {
            evaluateItem(tenant, item.id(), now);
        }
        refreshRequestCompletion(tenant, requestId, now);
        return requests.findRequest(tenant, submitted.id()).orElseThrow();
    }

    public RequestItem reevaluate(
            TenantContext tenant,
            UUID itemId,
            Instant now) {
        return evaluateItem(tenant, itemId, now);
    }

    public RequestItem finalizeApprovedItem(
            TenantContext tenant,
            UUID itemId,
            UUID approvedPlanId,
            Instant now) {
        RequestItem item = requests.findItem(tenant, itemId)
                .orElseThrow(() -> new AccessRequestCommandException(
                        "request_item_not_found",
                        "RequestItem was not found."));
        if (item.lifecycleState() == RequestItem.LifecycleState.APPLIED) {
            return item;
        }
        if (item.lifecycleState() == RequestItem.LifecycleState.AUTHORIZED) {
            return applyAuthorizedItem(tenant, item.id(), now);
        }
        if (item.lifecycleState()
                != RequestItem.LifecycleState.PENDING_APPROVAL
                || !Objects.equals(item.approvalPlanId(), approvedPlanId)) {
            throw new AccessRequestCommandException(
                    "request_item_approval_state_changed",
                    "RequestItem no longer matches the approved plan.");
        }

        AccessRequest request = requests.findRequest(
                        tenant, item.accessRequestId())
                .orElseThrow();
        if (!identities.identityExists(
                tenant, request.beneficiaryIdentityId())) {
            return deny(
                    tenant,
                    item,
                    "beneficiary_not_eligible",
                    now);
        }
        if (item.principalConstraintKind()
                == RequestItem.PrincipalConstraintKind.SPECIFIC) {
            var principal = identities.principal(
                    tenant, item.specificPrincipalId());
            if (principal.status()
                            != IdentityAccessReferenceQuery.Status.RESOLVED
                    || !request.beneficiaryIdentityId().equals(
                            principal.identityId())) {
                return deny(
                        tenant,
                        item,
                        "principal_beneficiary_mismatch",
                        now);
            }
        }

        var eligibilityResult =
                eligibility.evaluate(tenant, item);
        if (eligibilityResult.status()
                == AccessRequestEligibilityEvaluator.Status.UNAVAILABLE) {
            throw new AccessRequestCommandException(
                    eligibilityResult.code() == null
                            ? "eligibility_unavailable"
                            : eligibilityResult.code(),
                    "Mandatory eligibility evaluation is unavailable.");
        }
        if (eligibilityResult.status()
                == AccessRequestEligibilityEvaluator.Status.DENIED) {
            return deny(
                    tenant,
                    item,
                    eligibilityResult.code(),
                    now);
        }

        var requirement = approvalRequirements.resolve(
                tenant, item, now);
        if (requirement.status()
                == AccessRequestApprovalRequirementsResolver.Status.UNAVAILABLE) {
            throw new AccessRequestCommandException(
                    requirement.code() == null
                            ? "approval_requirements_unavailable"
                            : requirement.code(),
                    "Mandatory approval requirements are unavailable.");
        }
        if (requirement.status()
                == AccessRequestApprovalRequirementsResolver.Status.APPROVAL_REQUIRED
                && !requirementsMatch(
                        tenant,
                        approvedPlanId,
                        requirement.requirementsFingerprint(),
                        requirement.stages(),
                        requirement.deadlineAt())) {
            ApprovalSubject subject = new ApprovalSubject(
                    ApprovalSubject.Kind.ACCESS_REQUEST_ITEM,
                    item.id());
            var replacement = approvals.createPlan(
                    tenant,
                    subject,
                    requirement.requirementsFingerprint(),
                    requirement.stages(),
                    requirement.deadlineAt(),
                    now);
            long revision = item.revision();
            return transactions.required(() ->
                    requests.updateItemState(
                            tenant,
                            itemId,
                            RequestItem.LifecycleState.PENDING_APPROVAL,
                            replacement.id(),
                            null,
                            null,
                            revision,
                            now));
        }

        RequestItem authorized = authorize(
                tenant, item, approvedPlanId, now);
        return applyAuthorizedItem(
                tenant, authorized.id(), now);
    }

    private boolean requirementsMatch(
            TenantContext tenant,
            UUID planId,
            String requirementsFingerprint,
            List<ApprovalService.StageSpec> requiredStages,
            Instant requiredDeadlineAt) {
        var existingPlan = approvalRepository.findPlan(
                        tenant, planId)
                .orElseThrow();
        if (!existingPlan.requirementsFingerprint().equals(
                requirementsFingerprint)
                || !Objects.equals(
                        existingPlan.deadlineAt(),
                        requiredDeadlineAt)) {
            return false;
        }
        var existingStages = approvalRepository.findStages(
                tenant, planId);
        if (existingStages.size() != requiredStages.size()) {
            return false;
        }
        for (int i = 0; i < existingStages.size(); i++) {
            var existingStage = existingStages.get(i);
            var requiredStage = requiredStages.get(i);
            if (existingStage.decisionMode()
                    != requiredStage.decisionMode()) {
                return false;
            }
            var existingApprovers = approvalRepository
                    .findParticipants(tenant, existingStage.id())
                    .stream()
                    .map(io.wyrmgate.iam.governance.domain.ApprovalParticipant::approverIdentityId)
                    .collect(java.util.stream.Collectors.toSet());
            var requiredApprovers =
                    new java.util.HashSet<>(
                            requiredStage.approverIdentityIds());
            if (!existingApprovers.equals(requiredApprovers)) {
                return false;
            }
        }
        return true;
    }

    public RequestItem applyAuthorizedItem(
            TenantContext tenant,
            UUID itemId,
            Instant now) {
        RequestItem item = requests.findItem(tenant, itemId)
                .orElseThrow(() -> new AccessRequestCommandException(
                        "request_item_not_found",
                        "RequestItem was not found."));
        if (item.lifecycleState() == RequestItem.LifecycleState.APPLIED) {
            return item;
        }
        if (item.lifecycleState()
                != RequestItem.LifecycleState.AUTHORIZED) {
            throw new AccessRequestCommandException(
                    "request_item_not_authorized",
                    "Only an AUTHORIZED RequestItem can be applied.");
        }
        AccessRequest request = requests.findRequest(
                        tenant, item.accessRequestId())
                .orElseThrow();

        AccessAssignment assignment = access.applyRequestedAccess(
                tenant,
                new AccessIntentCommand.RequestedAccess(
                        item.id(),
                        request.beneficiaryIdentityId(),
                        item.targetKind() == RequestItem.TargetKind.ROLE
                                ? AccessIntentCommand.TargetKind.ROLE
                                : AccessIntentCommand.TargetKind.ENTITLEMENT,
                        item.roleId(),
                        item.entitlementId(),
                        item.principalConstraintKind()
                                == RequestItem.PrincipalConstraintKind.ANY
                                ? AccessAssignment.PrincipalConstraintKind.ANY
                                : AccessAssignment.PrincipalConstraintKind.SPECIFIC,
                        item.specificPrincipalId(),
                        item.validFrom(),
                        item.validUntil()),
                now);

        RequestItem applied = transactions.required(() -> {
            RequestItem current = requests.findItem(tenant, itemId)
                    .orElseThrow();
            if (current.lifecycleState()
                    == RequestItem.LifecycleState.APPLIED) {
                return current;
            }
            if (current.lifecycleState()
                    != RequestItem.LifecycleState.AUTHORIZED) {
                throw new AccessRequestCommandException(
                        "request_item_state_changed",
                        "RequestItem changed before Access application completed.");
            }
            return requests.updateItemState(
                    tenant,
                    itemId,
                    RequestItem.LifecycleState.APPLIED,
                    current.approvalPlanId(),
                    assignment.id(),
                    null,
                    current.revision(),
                    now);
        });
        refreshRequestCompletion(
                tenant, applied.accessRequestId(), now);
        return applied;
    }

    private RequestItem evaluateItem(
            TenantContext tenant,
            UUID itemId,
            Instant now) {
        RequestItem item = requests.findItem(tenant, itemId)
                .orElseThrow(() -> new AccessRequestCommandException(
                        "request_item_not_found",
                        "RequestItem was not found."));

        if (item.lifecycleState() == RequestItem.LifecycleState.SUBMITTED) {
            long submittedRevision = item.revision();
            item = transactions.required(() ->
                    requests.updateItemState(
                            tenant,
                            itemId,
                            RequestItem.LifecycleState.EVALUATING,
                            null,
                            null,
                            null,
                            submittedRevision,
                            now));
        }
        if (item.lifecycleState()
                != RequestItem.LifecycleState.EVALUATING) {
            return item;
        }

        AccessRequest request = requests.findRequest(
                        tenant, item.accessRequestId())
                .orElseThrow();
        if (!identities.identityExists(
                tenant, request.beneficiaryIdentityId())) {
            return deny(
                    tenant,
                    item,
                    "beneficiary_not_eligible",
                    now);
        }
        if (item.principalConstraintKind()
                == RequestItem.PrincipalConstraintKind.SPECIFIC) {
            var principal = identities.principal(
                    tenant, item.specificPrincipalId());
            if (principal.status()
                            != IdentityAccessReferenceQuery.Status.RESOLVED
                    || !request.beneficiaryIdentityId().equals(
                            principal.identityId())) {
                return deny(
                        tenant,
                        item,
                        "principal_beneficiary_mismatch",
                        now);
            }
        }

        var eligibilityResult =
                eligibility.evaluate(tenant, item);
        if (eligibilityResult.status()
                == AccessRequestEligibilityEvaluator.Status.UNAVAILABLE) {
            return item;
        }
        if (eligibilityResult.status()
                == AccessRequestEligibilityEvaluator.Status.DENIED) {
            return deny(
                    tenant,
                    item,
                    eligibilityResult.code(),
                    now);
        }

        var requirement = approvalRequirements.resolve(
                tenant, item, now);
        if (requirement.status()
                == AccessRequestApprovalRequirementsResolver.Status.UNAVAILABLE) {
            return item;
        }
        if (requirement.status()
                == AccessRequestApprovalRequirementsResolver.Status.NO_APPROVAL) {
            RequestItem authorized = authorize(
                    tenant, item, null, now);
            return applyAuthorizedItem(
                    tenant, authorized.id(), now);
        }

        ApprovalSubject subject = new ApprovalSubject(
                ApprovalSubject.Kind.ACCESS_REQUEST_ITEM,
                item.id());
        var existing = approvalRepository.findPendingBySubject(
                tenant, subject);
        var plan = existing.orElseGet(() ->
                approvals.createPlan(
                        tenant,
                        subject,
                        requirement.requirementsFingerprint(),
                        requirement.stages(),
                        requirement.deadlineAt(),
                        now));
        long evaluatingRevision = item.revision();
        UUID pendingItemId = item.id();
        return transactions.required(() ->
                requests.updateItemState(
                        tenant,
                        pendingItemId,
                        RequestItem.LifecycleState.PENDING_APPROVAL,
                        plan.id(),
                        null,
                        null,
                        evaluatingRevision,
                        now));
    }

    private RequestItem deny(
            TenantContext tenant,
            RequestItem item,
            String code,
            Instant now) {
        RequestItem denied = transactions.required(() ->
                requests.updateItemState(
                        tenant,
                        item.id(),
                        RequestItem.LifecycleState.DENIED,
                        item.approvalPlanId(),
                        null,
                        code,
                        item.revision(),
                        now));
        refreshRequestCompletion(
                tenant, denied.accessRequestId(), now);
        return denied;
    }

    RequestItem authorize(
            TenantContext tenant,
            RequestItem item,
            UUID approvalPlanId,
            Instant now) {
        return transactions.required(() ->
                requests.updateItemState(
                        tenant,
                        item.id(),
                        RequestItem.LifecycleState.AUTHORIZED,
                        approvalPlanId,
                        null,
                        null,
                        item.revision(),
                        now));
    }

    void refreshRequestCompletion(
            TenantContext tenant,
            UUID requestId,
            Instant now) {
        List<RequestItem> items =
                requests.findItems(tenant, requestId);
        boolean complete = !items.isEmpty()
                && items.stream().allMatch(item -> switch (
                        item.lifecycleState()) {
                    case APPLIED, DENIED, REJECTED,
                            CANCELLED, EXPIRED -> true;
                    default -> false;
                });
        if (!complete) return;

        AccessRequest request = requests.findRequest(
                        tenant, requestId)
                .orElseThrow();
        if (request.lifecycleState()
                == AccessRequest.LifecycleState.COMPLETED) {
            return;
        }
        if (request.lifecycleState()
                == AccessRequest.LifecycleState.CANCELLED) {
            return;
        }
        try {
            transactions.required(() ->
                    requests.updateRequestState(
                            tenant,
                            requestId,
                            AccessRequest.LifecycleState.COMPLETED,
                            request.revision(),
                            now,
                            null,
                            now));
        } catch (StaleWriteException concurrent) {
            // Another terminal item may have completed the envelope.
        }
    }

    public record ItemSpec(
            RequestItem.TargetKind targetKind,
            UUID roleId,
            UUID entitlementId,
            RequestItem.PrincipalConstraintKind principalConstraintKind,
            UUID specificPrincipalId,
            Instant validFrom,
            Instant validUntil) {

        RequestItem toItem(
                UUID id,
                UUID requestId,
                Instant now) {
            return new RequestItem(
                    id,
                    requestId,
                    targetKind,
                    roleId,
                    entitlementId,
                    principalConstraintKind,
                    specificPrincipalId,
                    validFrom,
                    validUntil,
                    RequestItem.LifecycleState.DRAFT,
                    null,
                    null,
                    null,
                    1,
                    now,
                    now);
        }
    }
}
