package io.wyrmgate.iam.api.identity;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.audit.application.AuditRecordDraft;
import io.wyrmgate.iam.audit.application.SecurityAuditPort;
import io.wyrmgate.iam.audit.domain.AuditOutcome;
import io.wyrmgate.iam.identity.application.IdentityMergeSplitRepository;
import io.wyrmgate.iam.identity.application.IdentityMergeSplitService;
import io.wyrmgate.iam.identity.domain.IdentityMergeOperation;
import io.wyrmgate.iam.identity.domain.IdentitySplitOperation;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.RegistrationKind;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** HTTP-adapter orchestration for authorized, causally-idempotent Identity merge/split commands. */
final class IdentityMergeSplitApiMutationService {

    private static final Logger LOG = LoggerFactory.getLogger(IdentityMergeSplitApiMutationService.class);

    private static final String MERGE_NAMESPACE = "api.identity.merge.v1";
    private static final String SPLIT_NAMESPACE = "api.identity.split.v1";

    private final AdministrativeAuthorizationService authorization;
    private final IdentityMergeSplitService service;
    private final IdentityMergeSplitRepository operations;
    private final JdbcIdempotencyRepository idempotency;
    private final TransactionExecutor transactions;
    private final SecurityAuditPort audit;
    private final IdGenerator ids;

    IdentityMergeSplitApiMutationService(
            AdministrativeAuthorizationService authorization,
            IdentityMergeSplitService service,
            IdentityMergeSplitRepository operations,
            JdbcIdempotencyRepository idempotency,
            TransactionExecutor transactions,
            SecurityAuditPort audit,
            IdGenerator ids) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.service = Objects.requireNonNull(service, "service");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    IdentityMergeOperation merge(
            AuthenticatedAdministrativeActor actor,
            UUID survivorIdentityId,
            long expectedSurvivorRevision,
            UUID absorbedIdentityId,
            long expectedAbsorbedRevision,
            String reason,
            String idempotencyKey,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        try {
            IdentityMergeOperation result = transactions.required(() -> {
            requireAllowed(
                    actor,
                    AdministrativePermissions.IDENTITY_MERGE,
                    new AdministrativeResource("identity", survivorIdentityId),
                    now,
                    correlationId);
            requireAllowed(
                    actor,
                    AdministrativePermissions.IDENTITY_MERGE,
                    new AdministrativeResource("identity", absorbedIdentityId),
                    now,
                    correlationId);
            var registration = idempotency.register(
                    actor.tenant(),
                    MERGE_NAMESPACE,
                    idempotencyKey,
                    fingerprint,
                    now,
                    null);
            if (registration.kind() == RegistrationKind.REPLAY) {
                return replayMerge(actor, registration, correlationId);
            }

            IdentityMergeOperation operation;
            try {
                operation = service.merge(
                        actor.tenant(),
                        survivorIdentityId,
                        expectedSurvivorRevision,
                        absorbedIdentityId,
                        expectedAbsorbedRevision,
                        reason,
                        now,
                        correlationId,
                        null);
            } catch (io.wyrmgate.iam.platform.persistence.StaleWriteException stale) {
                throw stale;
            } catch (IllegalArgumentException | IllegalStateException invalid) {
                throw IdentityApiException.conflict(
                        correlationId,
                        "identity_merge_conflict",
                        invalid.getMessage() == null
                                ? "Identity merge cannot be applied to current state."
                                : invalid.getMessage());
            }

            idempotency.complete(
                    actor.tenant(),
                    MERGE_NAMESPACE,
                    idempotencyKey,
                    fingerprint,
                    "identity-merge-operation",
                    operation.id(),
                    now);
            return operation;
            });
            recordOutcome(
                    actor, survivorIdentityId, "identity:merge", AuditOutcome.SUCCESS, now, correlationId);
            return result;
        } catch (RuntimeException failure) {
            recordOutcome(
                    actor,
                    survivorIdentityId,
                    "identity:merge",
                    auditOutcome(failure),
                    now,
                    correlationId);
            throw failure;
        }
    }

    IdentitySplitOperation split(
            AuthenticatedAdministrativeActor actor,
            UUID sourceIdentityId,
            long expectedSourceRevision,
            String newDisplayName,
            List<UUID> sourceRecordIds,
            List<UUID> principalIds,
            String reason,
            String idempotencyKey,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        try {
            IdentitySplitOperation result = transactions.required(() -> {
            requireAllowed(
                    actor,
                    AdministrativePermissions.IDENTITY_SPLIT,
                    new AdministrativeResource("identity", sourceIdentityId),
                    now,
                    correlationId);
            var registration = idempotency.register(
                    actor.tenant(),
                    SPLIT_NAMESPACE,
                    idempotencyKey,
                    fingerprint,
                    now,
                    null);
            if (registration.kind() == RegistrationKind.REPLAY) {
                return replaySplit(actor, registration, correlationId);
            }

            IdentityMergeSplitService.SplitResult splitResult;
            try {
                splitResult = service.split(
                        actor.tenant(),
                        sourceIdentityId,
                        expectedSourceRevision,
                        newDisplayName,
                        sourceRecordIds,
                        principalIds,
                        reason,
                        now,
                        correlationId,
                        null);
            } catch (io.wyrmgate.iam.platform.persistence.StaleWriteException stale) {
                throw stale;
            } catch (IllegalArgumentException | IllegalStateException invalid) {
                throw IdentityApiException.conflict(
                        correlationId,
                        "identity_split_conflict",
                        invalid.getMessage() == null
                                ? "Identity split cannot be applied to current state."
                                : invalid.getMessage());
            }

            IdentitySplitOperation operation = splitResult.operation();
            idempotency.complete(
                    actor.tenant(),
                    SPLIT_NAMESPACE,
                    idempotencyKey,
                    fingerprint,
                    "identity-split-operation",
                    operation.id(),
                    now);
            return operation;
            });
            recordOutcome(
                    actor, sourceIdentityId, "identity:split", AuditOutcome.SUCCESS, now, correlationId);
            return result;
        } catch (RuntimeException failure) {
            recordOutcome(
                    actor,
                    sourceIdentityId,
                    "identity:split",
                    auditOutcome(failure),
                    now,
                    correlationId);
            throw failure;
        }
    }

    private IdentityMergeOperation replayMerge(
            AuthenticatedAdministrativeActor actor,
            JdbcIdempotencyRepository.Registration registration,
            UUID correlationId) {
        requireCompletedResource(registration, "identity-merge-operation", correlationId);
        return operations.findMergeOperation(actor.tenant(), registration.resourceId())
                .orElseThrow(() -> new IllegalStateException(
                        "idempotent Identity merge result no longer exists"));
    }

    private IdentitySplitOperation replaySplit(
            AuthenticatedAdministrativeActor actor,
            JdbcIdempotencyRepository.Registration registration,
            UUID correlationId) {
        requireCompletedResource(registration, "identity-split-operation", correlationId);
        return operations.findSplitOperation(actor.tenant(), registration.resourceId())
                .orElseThrow(() -> new IllegalStateException(
                        "idempotent Identity split result no longer exists"));
    }

    private static void requireCompletedResource(
            JdbcIdempotencyRepository.Registration registration,
            String resourceType,
            UUID correlationId) {
        if (!"COMPLETED".equals(registration.operationState())) {
            throw IdentityApiException.conflict(
                    correlationId,
                    "idempotency_in_progress",
                    "The same idempotency key is already being processed.");
        }
        if (!resourceType.equals(registration.resourceType())
                || registration.resourceId() == null) {
            throw new IllegalStateException(
                    "completed merge/split idempotency record has invalid result");
        }
    }

    private static AuditOutcome auditOutcome(RuntimeException failure) {
        return failure instanceof IdentityApiException api
                        && api.status() == org.springframework.http.HttpStatus.FORBIDDEN
                ? AuditOutcome.DENIED
                : AuditOutcome.FAILURE;
    }

    private void recordOutcome(
            AuthenticatedAdministrativeActor actor,
            UUID identityId,
            String actionType,
            AuditOutcome outcome,
            Instant occurredAt,
            UUID correlationId) {
        try {
            audit.append(
                    actor.tenant(),
                    new AuditRecordDraft(
                            ids.nextId(),
                            occurredAt,
                            actor.identityId(),
                            actionType,
                            "identity",
                            identityId,
                            outcome,
                            correlationId,
                            null));
        } catch (RuntimeException auditFailure) {
            LOG.warn(
                    "Identity merge/split AuditRecord append failed; correlationId={} actionType={} outcome={}",
                    correlationId,
                    actionType,
                    outcome);
        }
    }

    private void requireAllowed(
            AuthenticatedAdministrativeActor actor,
            io.wyrmgate.iam.administration.domain.AdministrativePermission permission,
            AdministrativeResource resource,
            Instant now,
            UUID correlationId) {
        if (!authorization.authorize(actor, permission, resource, now).allowed()) {
            throw IdentityApiException.forbidden(correlationId);
        }
    }
}
