package io.wyrmgate.iam.api.governance;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.governance.application.AccessRequestCommandService;
import io.wyrmgate.iam.governance.application.AccessRequestModels.ItemSpec;
import io.wyrmgate.iam.governance.application.AccessRequestModels.RequestDetail;
import io.wyrmgate.iam.governance.application.AccessRequestRepository;
import io.wyrmgate.iam.governance.application.ApprovalCommandService;
import io.wyrmgate.iam.governance.application.ApprovalModels.ApprovalCase;
import io.wyrmgate.iam.governance.application.ApprovalModels.DecisionValue;
import io.wyrmgate.iam.governance.application.ApprovalRepository;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQuery;
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

    private final AdministrativeAuthorizationService authorization;
    private final IdentityAccessReferenceQuery identities;
    private final AccessRequestCommandService requests;
    private final AccessRequestRepository requestRepository;
    private final ApprovalCommandService approvals;
    private final ApprovalRepository approvalRepository;
    private final JdbcIdempotencyRepository idempotency;
    private final TransactionExecutor transactions;

    GovernanceApiMutationService(
            AdministrativeAuthorizationService authorization,
            IdentityAccessReferenceQuery identities,
            AccessRequestCommandService requests,
            AccessRequestRepository requestRepository,
            ApprovalCommandService approvals,
            ApprovalRepository approvalRepository,
            JdbcIdempotencyRepository idempotency,
            TransactionExecutor transactions) {
        this.authorization = Objects.requireNonNull(
                authorization, "authorization");
        this.identities = Objects.requireNonNull(
                identities, "identities");
        this.requests = Objects.requireNonNull(
                requests, "requests");
        this.requestRepository = Objects.requireNonNull(
                requestRepository, "requestRepository");
        this.approvals = Objects.requireNonNull(
                approvals, "approvals");
        this.approvalRepository = Objects.requireNonNull(
                approvalRepository, "approvalRepository");
        this.idempotency = Objects.requireNonNull(
                idempotency, "idempotency");
        this.transactions = Objects.requireNonNull(
                transactions, "transactions");
    }

    RequestDetail create(
            AuthenticatedAdministrativeActor actor,
            UUID beneficiaryIdentityId,
            List<ItemSpec> items,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return transactions.required(() -> {
            if (!identities.identityExists(
                    actor.tenant(), beneficiaryIdentityId)) {
                throw GovernanceApiException.notFound(
                        correlationId,
                        "beneficiary_not_found",
                        "The beneficiary Identity was not found.");
            }
            if (!actor.identityId().equals(
                    beneficiaryIdentityId)
                    && !authorization.authorize(
                            actor,
                            AdministrativePermissions
                                    .ACCESS_REQUEST_FOR_OTHERS,
                            AdministrativeResource.collection(
                                    "access-request"),
                            now).allowed()) {
                throw GovernanceApiException.forbidden(
                        correlationId);
            }
            Registration registration = idempotency.register(
                    actor.tenant(),
                    "api.governance.access-request.create.v1",
                    key,
                    fingerprint,
                    now,
                    null);
            if (registration.kind()
                    == RegistrationKind.REPLAY) {
                return replayRequest(
                        actor, registration, correlationId);
            }
            RequestDetail created = requests.createDraft(
                    actor.tenant(),
                    actor.identityId(),
                    beneficiaryIdentityId,
                    items,
                    now);
            idempotency.complete(
                    actor.tenant(),
                    "api.governance.access-request.create.v1",
                    key,
                    fingerprint,
                    "access-request",
                    created.request().id(),
                    now);
            return created;
        });
    }

    RequestDetail submit(
            AuthenticatedAdministrativeActor actor,
            UUID requestId,
            long expectedRevision,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return transactions.required(() -> {
            var current = requestRepository.findRequest(
                            actor.tenant(), requestId)
                    .orElseThrow(() ->
                            GovernanceApiException.notFound(
                                    correlationId));
            if (!actor.identityId().equals(
                    current.requesterIdentityId())) {
                throw GovernanceApiException.forbidden(
                        correlationId);
            }
            Registration registration = idempotency.register(
                    actor.tenant(),
                    "api.governance.access-request.submit.v1",
                    key,
                    fingerprint,
                    now,
                    null);
            if (registration.kind()
                    == RegistrationKind.REPLAY) {
                return replayRequest(
                        actor, registration, correlationId);
            }
            RequestDetail submitted = requests.submit(
                    actor.tenant(),
                    requestId,
                    expectedRevision,
                    now);
            idempotency.complete(
                    actor.tenant(),
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
            Registration registration = idempotency.register(
                    actor.tenant(),
                    "api.governance.approval.decision.v1",
                    key,
                    fingerprint,
                    now,
                    null);
            if (registration.kind()
                    == RegistrationKind.REPLAY) {
                return replayApproval(
                        actor, registration, correlationId);
            }
            ApprovalCase updated = approvals.decide(
                    actor.tenant(),
                    approvalCaseId,
                    actor.identityId(),
                    decision,
                    reason,
                    expectedRevision,
                    now);
            idempotency.complete(
                    actor.tenant(),
                    "api.governance.approval.decision.v1",
                    key,
                    fingerprint,
                    "approval-case",
                    approvalCaseId,
                    now);
            return updated;
        });
    }

    private RequestDetail replayRequest(
            AuthenticatedAdministrativeActor actor,
            Registration registration,
            UUID correlationId) {
        requireCompleted(
                registration,
                "access-request",
                correlationId);
        UUID requestId = registration.resourceId();
        var request = requestRepository.findRequest(
                        actor.tenant(), requestId)
                .orElseThrow(() -> new IllegalStateException(
                        "idempotent AccessRequest result no longer exists"));
        return new RequestDetail(
                request,
                requestRepository.findItems(
                        actor.tenant(), requestId));
    }

    private ApprovalCase replayApproval(
            AuthenticatedAdministrativeActor actor,
            Registration registration,
            UUID correlationId) {
        requireCompleted(
                registration,
                "approval-case",
                correlationId);
        return approvalRepository.findCase(
                        actor.tenant(),
                        registration.resourceId())
                .orElseThrow(() -> new IllegalStateException(
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
