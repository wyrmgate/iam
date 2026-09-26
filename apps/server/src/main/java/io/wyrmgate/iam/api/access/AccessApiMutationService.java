package io.wyrmgate.iam.api.access;

import io.wyrmgate.iam.access.application.AccessAssignmentCommandService;
import io.wyrmgate.iam.access.application.AccessAssignmentRepository;
import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.Registration;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.RegistrationKind;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

final class AccessApiMutationService {

    private final AdministrativeAuthorizationService authorization;
    private final AccessAssignmentCommandService commands;
    private final AccessAssignmentRepository assignments;
    private final JdbcIdempotencyRepository idempotency;
    private final TransactionExecutor transactions;

    AccessApiMutationService(
            AdministrativeAuthorizationService authorization,
            AccessAssignmentCommandService commands,
            AccessAssignmentRepository assignments,
            JdbcIdempotencyRepository idempotency,
            TransactionExecutor transactions) {
        this.authorization = Objects.requireNonNull(
                authorization, "authorization");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.assignments = Objects.requireNonNull(
                assignments, "assignments");
        this.idempotency = Objects.requireNonNull(
                idempotency, "idempotency");
        this.transactions = Objects.requireNonNull(
                transactions, "transactions");
    }

    AccessAssignment create(
            AuthenticatedAdministrativeActor actor,
            UUID identityId,
            AccessAssignment.TargetKind targetKind,
            UUID roleId,
            UUID entitlementId,
            AccessAssignment.PrincipalConstraintKind constraintKind,
            UUID specificPrincipalId,
            Instant validFrom,
            Instant validUntil,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return transactions.required(() -> {
            require(
                    actor,
                    AdministrativePermissions.ACCESS_ASSIGNMENT_CREATE,
                    AdministrativeResource.collection("access-assignment"),
                    now,
                    correlationId);
            Registration registration = register(
                    actor,
                    "api.access.assignment.create.v1",
                    key,
                    fingerprint,
                    now);
            if (registration.kind() == RegistrationKind.REPLAY) {
                return replay(actor, registration, correlationId);
            }
            AccessAssignment created =
                    targetKind == AccessAssignment.TargetKind.ROLE
                            ? commands.createRoleAssignment(
                                    actor.tenant(),
                                    identityId,
                                    roleId,
                                    constraintKind,
                                    specificPrincipalId,
                                    validFrom,
                                    validUntil,
                                    now)
                            : commands.createEntitlementAssignment(
                                    actor.tenant(),
                                    identityId,
                                    entitlementId,
                                    constraintKind,
                                    specificPrincipalId,
                                    validFrom,
                                    validUntil,
                                    now);
            complete(
                    actor,
                    "api.access.assignment.create.v1",
                    key,
                    fingerprint,
                    created.id(),
                    now);
            return created;
        });
    }

    AccessAssignment suspend(
            AuthenticatedAdministrativeActor actor,
            UUID assignmentId,
            long expectedRevision,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return lifecycle(
                actor,
                assignmentId,
                expectedRevision,
                key,
                fingerprint,
                now,
                correlationId,
                AdministrativePermissions.ACCESS_ASSIGNMENT_SUSPEND,
                "api.access.assignment.suspend.v1",
                () -> commands.suspend(
                        actor.tenant(),
                        assignmentId,
                        expectedRevision,
                        now));
    }

    AccessAssignment resume(
            AuthenticatedAdministrativeActor actor,
            UUID assignmentId,
            long expectedRevision,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return lifecycle(
                actor,
                assignmentId,
                expectedRevision,
                key,
                fingerprint,
                now,
                correlationId,
                AdministrativePermissions.ACCESS_ASSIGNMENT_RESUME,
                "api.access.assignment.resume.v1",
                () -> commands.resume(
                        actor.tenant(),
                        assignmentId,
                        expectedRevision,
                        now));
    }

    AccessAssignment cancel(
            AuthenticatedAdministrativeActor actor,
            UUID assignmentId,
            long expectedRevision,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return lifecycle(
                actor,
                assignmentId,
                expectedRevision,
                key,
                fingerprint,
                now,
                correlationId,
                AdministrativePermissions.ACCESS_ASSIGNMENT_CANCEL,
                "api.access.assignment.cancel.v1",
                () -> commands.cancel(
                        actor.tenant(),
                        assignmentId,
                        expectedRevision,
                        now));
    }

    AccessAssignment revoke(
            AuthenticatedAdministrativeActor actor,
            UUID assignmentId,
            long expectedRevision,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return lifecycle(
                actor,
                assignmentId,
                expectedRevision,
                key,
                fingerprint,
                now,
                correlationId,
                AdministrativePermissions.ACCESS_ASSIGNMENT_REVOKE,
                "api.access.assignment.revoke.v1",
                () -> commands.revoke(
                        actor.tenant(),
                        assignmentId,
                        expectedRevision,
                        now));
    }

    private AccessAssignment lifecycle(
            AuthenticatedAdministrativeActor actor,
            UUID assignmentId,
            long expectedRevision,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId,
            AdministrativePermission permission,
            String namespace,
            Supplier<AccessAssignment> action) {
        return transactions.required(() -> {
            require(
                    actor,
                    permission,
                    new AdministrativeResource(
                            "access-assignment",
                            assignmentId),
                    now,
                    correlationId);
            ensureAssignment(
                    actor, assignmentId, correlationId);
            Registration registration = register(
                    actor,
                    namespace,
                    key,
                    fingerprint,
                    now);
            if (registration.kind() == RegistrationKind.REPLAY) {
                return replay(actor, registration, correlationId);
            }
            AccessAssignment updated = action.get();
            complete(
                    actor,
                    namespace,
                    key,
                    fingerprint,
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
            UUID resourceId,
            Instant now) {
        idempotency.complete(
                actor.tenant(),
                namespace,
                key,
                fingerprint,
                "access-assignment",
                resourceId,
                now);
    }

    private AccessAssignment replay(
            AuthenticatedAdministrativeActor actor,
            Registration registration,
            UUID correlationId) {
        if (!"COMPLETED".equals(registration.operationState())) {
            throw AccessApiException.conflict(
                    correlationId,
                    "idempotency_in_progress",
                    "The same idempotency key is already being processed.");
        }
        if (!"access-assignment".equals(
                    registration.resourceType())
                || registration.resourceId() == null) {
            throw new IllegalStateException(
                    "completed Access idempotency result is invalid");
        }
        return assignments.findById(
                        actor.tenant(),
                        registration.resourceId())
                .orElseThrow(() -> new IllegalStateException(
                        "idempotent AccessAssignment result no longer exists"));
    }

    private void require(
            AuthenticatedAdministrativeActor actor,
            AdministrativePermission permission,
            AdministrativeResource resource,
            Instant now,
            UUID correlationId) {
        if (!authorization.authorize(
                actor, permission, resource, now).allowed()) {
            throw AccessApiException.forbidden(correlationId);
        }
    }

    private void ensureAssignment(
            AuthenticatedAdministrativeActor actor,
            UUID assignmentId,
            UUID correlationId) {
        if (assignments.findById(
                actor.tenant(), assignmentId).isEmpty()) {
            throw AccessApiException.notFound(correlationId);
        }
    }
}
