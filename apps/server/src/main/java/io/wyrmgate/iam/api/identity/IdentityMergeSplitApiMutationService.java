package io.wyrmgate.iam.api.identity;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.identity.application.IdentityMergeSplitRepository;
import io.wyrmgate.iam.identity.application.IdentityMergeSplitService;
import io.wyrmgate.iam.identity.domain.IdentityMergeOperation;
import io.wyrmgate.iam.identity.domain.IdentitySplitOperation;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.RegistrationKind;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** HTTP-adapter orchestration for authorized, causally-idempotent Identity merge/split commands. */
final class IdentityMergeSplitApiMutationService {

    private static final String MERGE_NAMESPACE = "api.identity.merge.v1";
    private static final String SPLIT_NAMESPACE = "api.identity.split.v1";

    private final AdministrativeAuthorizationService authorization;
    private final IdentityMergeSplitService service;
    private final IdentityMergeSplitRepository operations;
    private final JdbcIdempotencyRepository idempotency;
    private final TransactionExecutor transactions;

    IdentityMergeSplitApiMutationService(
            AdministrativeAuthorizationService authorization,
            IdentityMergeSplitService service,
            IdentityMergeSplitRepository operations,
            JdbcIdempotencyRepository idempotency,
            TransactionExecutor transactions) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.service = Objects.requireNonNull(service, "service");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
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
        return transactions.required(() -> {
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
        return transactions.required(() -> {
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

            IdentityMergeSplitService.SplitResult result;
            try {
                result = service.split(
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

            IdentitySplitOperation operation = result.operation();
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
