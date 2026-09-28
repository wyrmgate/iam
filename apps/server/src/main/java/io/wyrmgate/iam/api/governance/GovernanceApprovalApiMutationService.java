package io.wyrmgate.iam.api.governance;

import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.governance.application.ApprovalQueryService;
import io.wyrmgate.iam.governance.application.ApprovalService;
import io.wyrmgate.iam.governance.domain.ApprovalCase;
import io.wyrmgate.iam.governance.domain.ApprovalDecision;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.Registration;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.RegistrationKind;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

final class GovernanceApprovalApiMutationService {

    private final ApprovalService approvals;
    private final ApprovalQueryService queries;
    private final JdbcIdempotencyRepository idempotency;
    private final TransactionExecutor transactions;

    GovernanceApprovalApiMutationService(
            ApprovalService approvals,
            ApprovalQueryService queries,
            JdbcIdempotencyRepository idempotency,
            TransactionExecutor transactions) {
        this.approvals = Objects.requireNonNull(
                approvals, "approvals");
        this.queries = Objects.requireNonNull(
                queries, "queries");
        this.idempotency = Objects.requireNonNull(
                idempotency, "idempotency");
        this.transactions = Objects.requireNonNull(
                transactions, "transactions");
    }

    ApprovalCase decide(
            AuthenticatedAdministrativeActor actor,
            UUID approvalCaseId,
            ApprovalDecision.Decision decision,
            long expectedRevision,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        String namespace =
                decision == ApprovalDecision.Decision.APPROVE
                        ? "api.governance.approval.approve.v1"
                        : "api.governance.approval.reject.v1";
        return transactions.required(() -> {
            Registration registration = idempotency.register(
                    actor.tenant(),
                    namespace,
                    key,
                    fingerprint,
                    now,
                    null);
            if (registration.kind() == RegistrationKind.REPLAY) {
                return replay(
                        actor, registration, correlationId);
            }
            ApprovalCase updated = approvals.decide(
                    actor.tenant(),
                    approvalCaseId,
                    actor.identityId(),
                    decision,
                    expectedRevision,
                    now,
                    correlationId,
                    null);
            idempotency.complete(
                    actor.tenant(),
                    namespace,
                    key,
                    fingerprint,
                    "approval-case",
                    updated.id(),
                    now);
            return updated;
        });
    }

    private ApprovalCase replay(
            AuthenticatedAdministrativeActor actor,
            Registration registration,
            UUID correlationId) {
        if (!"COMPLETED".equals(registration.operationState())) {
            throw GovernanceApprovalApiException.conflict(
                    correlationId,
                    "idempotency_in_progress",
                    "The same idempotency key is already being processed.");
        }
        if (!"approval-case".equals(
                    registration.resourceType())
                || registration.resourceId() == null) {
            throw new IllegalStateException(
                    "completed approval idempotency result is invalid");
        }
        return queries.findCase(
                        actor.tenant(),
                        registration.resourceId())
                .orElseThrow(() -> new IllegalStateException(
                        "idempotent ApprovalCase result no longer exists"));
    }
}
