package io.wyrmgate.iam.api.governance;

import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.governance.application.AccessRequestCommandService;
import io.wyrmgate.iam.governance.application.AccessRequestModels.ItemSpec;
import io.wyrmgate.iam.governance.application.AccessRequestModels.RequestDetail;
import io.wyrmgate.iam.governance.application.AccessRequestRepository;
import io.wyrmgate.iam.governance.application.ApprovalCommandService;
import io.wyrmgate.iam.governance.application.ApprovalModels.ApprovalCase;
import io.wyrmgate.iam.governance.application.ApprovalModels.DecisionValue;
import io.wyrmgate.iam.governance.application.ApprovalQueryService;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.Registration;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.RegistrationKind;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

final class GovernanceApiMutationService {

    private final AccessRequestCommandService requests;
    private final AccessRequestRepository requestRepository;
    private final ApprovalCommandService approvals;
    private final ApprovalQueryService approvalQueries;
    private final JdbcIdempotencyRepository idempotency;
    private final TransactionExecutor transactions;

    GovernanceApiMutationService(
            AccessRequestCommandService requests,
            AccessRequestRepository requestRepository,
            ApprovalCommandService approvals,
            ApprovalQueryService approvalQueries,
            JdbcIdempotencyRepository idempotency,
            TransactionExecutor transactions) {
        this.requests = Objects.requireNonNull(
                requests, "requests");
        this.requestRepository = Objects.requireNonNull(
                requestRepository, "requestRepository");
        this.approvals = Objects.requireNonNull(
                approvals, "approvals");
        this.approvalQueries = Objects.requireNonNull(
                approvalQueries, "approvalQueries");
        this.idempotency = Objects.requireNonNull(
                idempotency, "idempotency");
        this.transactions = Objects.requireNonNull(
                transactions, "transactions");
    }

    RequestDetail createRequest(
            AuthenticatedAdministrativeActor actor,
            UUID beneficiaryIdentityId,
            List<ItemSpec> itemSpecs,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return transactions.required(() -> {
            if (!actor.identityId().equals(
                    beneficiaryIdentityId)) {
                throw GovernanceApiException.forbidden(
                        correlationId);
            }
            Registration registration = register(
                    actor,
                    "api.governance.access-request.create.v1",
                    key,
                    fingerprint,
                    now);
            if (registration.kind()
                    == RegistrationKind.REPLAY) {
                return replayRequest(
                        actor,
                        registration,
                        correlationId);
            }
            RequestDetail created =
                    requests.createDraft(
                            actor.tenant(),
                            actor.identityId(),
                            beneficiaryIdentityId,
                            itemSpecs,
                            now);
            complete(
                    actor,
                    "api.governance.access-request.create.v1",
                    key,
                    fingerprint,
                    "access-request",
                    created.request().id(),
                    now);
            return created;
        });
    }

    RequestDetail submitRequest(
            AuthenticatedAdministrativeActor actor,
            UUID requestId,
            long expectedRevision,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return transactions.required(() -> {
            var existing = requestRepository.findRequest(
                            actor.tenant(), requestId)
                    .orElseThrow(() ->
                            GovernanceApiException.notFound(
                                    correlationId));
            if (!actor.identityId().equals(
                    existing.requesterIdentityId())) {
                throw GovernanceApiException.forbidden(
                        correlationId);
            }
            Registration registration = register(
                    actor,
                    "api.governance.access-request.submit.v1",
                    key,
                    fingerprint,
                    now);
            if (registration.kind()
                    == RegistrationKind.REPLAY) {
                return replayRequest(
                        actor,
                        registration,
                        correlationId);
            }
            RequestDetail submitted = requests.submit(
                    actor.tenant(),
                    requestId,
                    expectedRevision,
                    now);
            complete(
                    actor,
                    "api.governance.access-request.submit.v1",
                    key,
                    fingerprint,
                    "access-request",
                    requestId,
                    now);
            return submitted;
        });
    }

    ApprovalCase decide(
            AuthenticatedAdministrativeActor actor,
            UUID approvalCaseId,
            DecisionValue decision,
            String reason,
            long expectedRevision,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return transactions.required(() -> {
            if (approvalQueries.findCase(
                    actor.tenant(),
                    approvalCaseId).isEmpty()) {
                throw GovernanceApiException.notFound(
                        correlationId);
            }
            Registration registration = register(
                    actor,
                    "api.governance.approval.decision.v1",
                    key,
                    fingerprint,
                    now);
            if (registration.kind()
                    == RegistrationKind.REPLAY) {
                return replayApproval(
                        actor,
                        registration,
                        correlationId);
            }
            ApprovalCase updated = approvals.decide(
                    actor.tenant(),
                    approvalCaseId,
                    actor.identityId(),
                    decision,
                    reason,
                    expectedRevision,
                    now);
            complete(
                    actor,
                    "api.governance.approval.decision.v1",
                    key,
                    fingerprint,
                    "approval-case",
                    updated.id(),
                    now);
            return updated;
        });
    }

    private Registration register(
            AuthenticatedAdministrativeActor actor,
            String namespace,
            String key,
            RequestFingerprint fingerprint,
            Instant now) {
        return idempotency.register(
                actor.tenant(),
                namespace,
                key,
                fingerprint,
                now,
                null);
    }

    private void complete(
            AuthenticatedAdministrativeActor actor,
            String namespace,
            String key,
            RequestFingerprint fingerprint,
            String resourceType,
            UUID resourceId,
            Instant now) {
        idempotency.complete(
                actor.tenant(),
                namespace,
                key,
                fingerprint,
                resourceType,
                resourceId,
                now);
    }

    private RequestDetail replayRequest(
            AuthenticatedAdministrativeActor actor,
            Registration registration,
            UUID correlationId) {
        requireCompleted(
                registration,
                "access-request",
                correlationId);
        return requests.find(
                actor.tenant(),
                registration.resourceId());
    }

    private ApprovalCase replayApproval(
            AuthenticatedAdministrativeActor actor,
            Registration registration,
            UUID correlationId) {
        requireCompleted(
                registration,
                "approval-case",
                correlationId);
        return approvalQueries.findCase(
                        actor.tenant(),
                        registration.resourceId())
                .orElseThrow(() ->
                        new IllegalStateException(
                                "idempotent ApprovalCase result no longer exists"));
    }

    private static void requireCompleted(
            Registration registration,
            String resourceType,
            UUID correlationId) {
        if (!"COMPLETED".equals(
                registration.operationState())) {
            throw GovernanceApiException.conflict(
                    correlationId,
                    "idempotency_in_progress",
                    "The same idempotency key is already being processed.");
        }
        if (!resourceType.equals(
                    registration.resourceType())
                || registration.resourceId() == null) {
            throw new IllegalStateException(
                    "completed Governance idempotency result is invalid");
        }
    }
}
