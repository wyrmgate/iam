package io.wyrmgate.iam.api.administration;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorityException;
import io.wyrmgate.iam.administration.application.AdministrativeAuthorityService;
import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassService;
import io.wyrmgate.iam.administration.application.AdministrativeElevationService;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativeBreakGlassOperation;
import io.wyrmgate.iam.administration.domain.AdministrativeBreakGlassState;
import io.wyrmgate.iam.administration.domain.AdministrativeDelegation;
import io.wyrmgate.iam.administration.domain.AdministrativeElevation;
import io.wyrmgate.iam.administration.domain.AdministrativeElevationState;
import io.wyrmgate.iam.administration.domain.AdministrativeGrant;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativeRole;
import io.wyrmgate.iam.administration.domain.AdministrativeScope;
import io.wyrmgate.iam.audit.application.AuditRecordDraft;
import io.wyrmgate.iam.audit.application.SecurityAuditPort;
import io.wyrmgate.iam.audit.domain.AuditOutcome;
import io.wyrmgate.iam.governance.application.ApprovalCommandException;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.Registration;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.RegistrationKind;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class AdministrationApiMutationService {

    private static final Logger LOG =
            LoggerFactory.getLogger(AdministrationApiMutationService.class);

    private final AdministrativeAuthorityService authority;
    private final AdministrativeElevationService elevations;
    private final AdministrativeBreakGlassService breakGlass;
    private final JdbcIdempotencyRepository idempotency;
    private final TransactionExecutor transactions;
    private final SecurityAuditPort audit;
    private final IdGenerator ids;

    AdministrationApiMutationService(
            AdministrativeAuthorityService authority,
            AdministrativeElevationService elevations,
            AdministrativeBreakGlassService breakGlass,
            JdbcIdempotencyRepository idempotency,
            TransactionExecutor transactions,
            SecurityAuditPort audit,
            IdGenerator ids) {
        this.authority = Objects.requireNonNull(authority, "authority");
        this.elevations = Objects.requireNonNull(elevations, "elevations");
        this.breakGlass = Objects.requireNonNull(breakGlass, "breakGlass");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    AdministrativeRole createRole(
            AuthenticatedAdministrativeActor actor,
            String code,
            String name,
            Set<AdministrativePermission> permissions,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        return atomic(
                actor,
                "api.administration.role.create.v1",
                "administrative-role",
                key,
                fingerprint,
                now,
                correlationId,
                causationId,
                "administrative-role:create",
                () -> authority.createRole(actor, code, name, permissions, now),
                AdministrativeRole::id,
                id -> authority.getRole(actor, id, now));
    }

    AdministrativeRole renameRole(
            AuthenticatedAdministrativeActor actor,
            UUID roleId,
            String name,
            long expectedRevision,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        return atomic(
                actor,
                "api.administration.role.rename.v1",
                "administrative-role",
                key,
                fingerprint,
                now,
                correlationId,
                causationId,
                "administrative-role:rename",
                () -> authority.renameRole(
                        actor, roleId, name, expectedRevision, now),
                AdministrativeRole::id,
                id -> authority.getRole(actor, id, now));
    }

    AdministrativeRole addRolePermission(
            AuthenticatedAdministrativeActor actor,
            UUID roleId,
            AdministrativePermission permission,
            long expectedRevision,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        return atomic(
                actor,
                "api.administration.role.permission.add.v1",
                "administrative-role",
                key,
                fingerprint,
                now,
                correlationId,
                causationId,
                "administrative-role:add-permission",
                () -> authority.addRolePermission(
                        actor,
                        roleId,
                        permission,
                        expectedRevision,
                        now),
                AdministrativeRole::id,
                id -> authority.getRole(actor, id, now));
    }

    AdministrativeRole removeRolePermission(
            AuthenticatedAdministrativeActor actor,
            UUID roleId,
            AdministrativePermission permission,
            long expectedRevision,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        return atomic(
                actor,
                "api.administration.role.permission.remove.v1",
                "administrative-role",
                key,
                fingerprint,
                now,
                correlationId,
                causationId,
                "administrative-role:remove-permission",
                () -> authority.removeRolePermission(
                        actor,
                        roleId,
                        permission,
                        expectedRevision,
                        now),
                AdministrativeRole::id,
                id -> authority.getRole(actor, id, now));
    }

    AdministrativeGrant createGrant(
            AuthenticatedAdministrativeActor actor,
            UUID beneficiaryIdentityId,
            UUID roleId,
            AdministrativeScope scope,
            Instant validFrom,
            Instant validUntil,
            boolean grantable,
            boolean delegable,
            UUID authorityBasisGrantId,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        return atomic(
                actor,
                "api.administration.grant.create.v1",
                "administrative-grant",
                key,
                fingerprint,
                now,
                correlationId,
                causationId,
                "administrative-grant:create",
                () -> authority.createGrant(
                        actor,
                        beneficiaryIdentityId,
                        roleId,
                        scope,
                        validFrom,
                        validUntil,
                        grantable,
                        delegable,
                        authorityBasisGrantId,
                        now),
                AdministrativeGrant::id,
                id -> authority.getGrant(actor, id, now));
    }

    AdministrativeGrant revokeGrant(
            AuthenticatedAdministrativeActor actor,
            UUID grantId,
            long expectedRevision,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        return atomic(
                actor,
                "api.administration.grant.revoke.v1",
                "administrative-grant",
                key,
                fingerprint,
                now,
                correlationId,
                causationId,
                "administrative-grant:revoke",
                () -> authority.revokeGrant(
                        actor, grantId, expectedRevision, now),
                AdministrativeGrant::id,
                id -> authority.getGrant(actor, id, now));
    }

    AdministrativeDelegation createDelegation(
            AuthenticatedAdministrativeActor actor,
            UUID delegateIdentityId,
            UUID sourceGrantId,
            AdministrativeScope scope,
            Instant validFrom,
            Instant validUntil,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        return atomic(
                actor,
                "api.administration.delegation.create.v1",
                "administrative-delegation",
                key,
                fingerprint,
                now,
                correlationId,
                causationId,
                "administrative-delegation:create",
                () -> authority.createDelegation(
                        actor,
                        delegateIdentityId,
                        sourceGrantId,
                        scope,
                        validFrom,
                        validUntil,
                        correlationId,
                        causationId,
                        now),
                AdministrativeDelegation::id,
                id -> authority.getDelegation(actor, id, now));
    }

    AdministrativeDelegation revokeDelegation(
            AuthenticatedAdministrativeActor actor,
            UUID delegationId,
            long expectedRevision,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        return atomic(
                actor,
                "api.administration.delegation.revoke.v1",
                "administrative-delegation",
                key,
                fingerprint,
                now,
                correlationId,
                causationId,
                "administrative-delegation:revoke",
                () -> authority.revokeDelegation(
                        actor,
                        delegationId,
                        expectedRevision,
                        now),
                AdministrativeDelegation::id,
                id -> authority.getDelegation(actor, id, now));
    }

    AdministrativeElevation requestElevation(
            AuthenticatedAdministrativeActor actor,
            UUID beneficiaryIdentityId,
            UUID roleId,
            AdministrativeScope scope,
            Instant validFrom,
            Instant validUntil,
            UUID authorityBasisGrantId,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        return atomic(
                actor,
                "api.administration.elevation.request.v1",
                "administrative-elevation",
                key,
                fingerprint,
                now,
                correlationId,
                causationId,
                "administrative-elevation:request",
                () -> elevations.request(
                        actor,
                        beneficiaryIdentityId,
                        roleId,
                        scope,
                        validFrom,
                        validUntil,
                        authorityBasisGrantId,
                        correlationId,
                        causationId,
                        now),
                AdministrativeElevation::id,
                id -> elevations.get(actor, id, now));
    }

    AdministrativeElevation requestElevationApproval(
            AuthenticatedAdministrativeActor actor,
            UUID elevationId,
            long expectedRevision,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        return resumableElevation(
                actor,
                elevationId,
                "api.administration.elevation.request-approval.v1",
                key,
                fingerprint,
                now,
                correlationId,
                causationId,
                "administrative-elevation:request-approval",
                current -> current.state()
                                == AdministrativeElevationState.PENDING_APPROVAL
                        ? current
                        : elevations.requestApproval(
                                actor,
                                elevationId,
                                expectedRevision,
                                now));
    }

    AdministrativeElevation applyElevation(
            AuthenticatedAdministrativeActor actor,
            UUID elevationId,
            long expectedRevision,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        return resumableElevation(
                actor,
                elevationId,
                "api.administration.elevation.apply.v1",
                key,
                fingerprint,
                now,
                correlationId,
                causationId,
                "administrative-elevation:apply",
                current -> current.state() == AdministrativeElevationState.ACTIVE
                                || current.state()
                                        == AdministrativeElevationState.DENIED
                        ? current
                        : elevations.apply(
                                actor,
                                elevationId,
                                expectedRevision,
                                now));
    }

    AdministrativeElevation cancelElevation(
            AuthenticatedAdministrativeActor actor,
            UUID elevationId,
            long expectedRevision,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        return atomic(
                actor,
                "api.administration.elevation.cancel.v1",
                "administrative-elevation",
                key,
                fingerprint,
                now,
                correlationId,
                causationId,
                "administrative-elevation:cancel",
                () -> elevations.cancel(
                        actor,
                        elevationId,
                        expectedRevision,
                        now),
                AdministrativeElevation::id,
                id -> elevations.get(actor, id, now));
    }

    AdministrativeElevation revokeElevation(
            AuthenticatedAdministrativeActor actor,
            UUID elevationId,
            long expectedRevision,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        return atomic(
                actor,
                "api.administration.elevation.revoke.v1",
                "administrative-elevation",
                key,
                fingerprint,
                now,
                correlationId,
                causationId,
                "administrative-elevation:revoke",
                () -> elevations.revoke(
                        actor,
                        elevationId,
                        expectedRevision,
                        now),
                AdministrativeElevation::id,
                id -> elevations.get(actor, id, now));
    }

    AdministrativeBreakGlassOperation activateBreakGlass(
            AuthenticatedAdministrativeActor actor,
            UUID roleId,
            AdministrativeScope scope,
            Instant validUntil,
            String reason,
            String incidentReference,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        Registration registration = transactions.required(() ->
                idempotency.register(
                        actor.tenant(),
                        "api.administration.break-glass.activate.v1",
                        key,
                        fingerprint,
                        now,
                        null));
        UUID operationId = registration.recordId();

        if (registration.kind() == RegistrationKind.REPLAY
                && "COMPLETED".equals(registration.operationState())) {
            requireCompleted(
                    registration,
                    "administrative-break-glass-operation",
                    correlationId);
            return breakGlass.get(
                    actor, registration.resourceId(), now);
        }

        AdministrativeBreakGlassOperation existing =
                findBreakGlass(actor, operationId, now);
        AdministrativeBreakGlassOperation result =
                existing != null
                        ? existing
                        : breakGlass.activate(
                                operationId,
                                actor,
                                roleId,
                                scope,
                                validUntil,
                                reason,
                                incidentReference,
                                correlationId,
                                causationId,
                                now);
        transactions.required(() -> {
            idempotency.complete(
                    actor.tenant(),
                    "api.administration.break-glass.activate.v1",
                    key,
                    fingerprint,
                    "administrative-break-glass-operation",
                    result.id(),
                    now);
            return null;
        });
        return result;
    }

    AdministrativeBreakGlassOperation revokeBreakGlass(
            AuthenticatedAdministrativeActor actor,
            UUID operationId,
            long expectedRevision,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        Registration registration = transactions.required(() ->
                idempotency.register(
                        actor.tenant(),
                        "api.administration.break-glass.revoke.v1",
                        key,
                        fingerprint,
                        now,
                        null));
        if (registration.kind() == RegistrationKind.REPLAY
                && "COMPLETED".equals(registration.operationState())) {
            requireCompleted(
                    registration,
                    "administrative-break-glass-operation",
                    correlationId);
            return breakGlass.get(
                    actor, registration.resourceId(), now);
        }

        AdministrativeBreakGlassOperation current =
                breakGlass.get(actor, operationId, now);
        AdministrativeBreakGlassOperation result =
                current.state() == AdministrativeBreakGlassState.REVOKED
                        ? current
                        : breakGlass.revoke(
                                actor,
                                operationId,
                                expectedRevision,
                                correlationId,
                                causationId,
                                now);
        transactions.required(() -> {
            idempotency.complete(
                    actor.tenant(),
                    "api.administration.break-glass.revoke.v1",
                    key,
                    fingerprint,
                    "administrative-break-glass-operation",
                    result.id(),
                    now);
            return null;
        });
        return result;
    }

    private AdministrativeElevation resumableElevation(
            AuthenticatedAdministrativeActor actor,
            UUID elevationId,
            String namespace,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId,
            UUID causationId,
            String auditAction,
            Function<AdministrativeElevation, AdministrativeElevation>
                    command) {
        try {
            Registration registration = transactions.required(() ->
                    idempotency.register(
                            actor.tenant(),
                            namespace,
                            key,
                            fingerprint,
                            now,
                            null));
            if (registration.kind() == RegistrationKind.REPLAY
                    && "COMPLETED".equals(
                            registration.operationState())) {
                requireCompleted(
                        registration,
                        "administrative-elevation",
                        correlationId);
                AdministrativeElevation replay =
                        elevations.get(
                                actor,
                                registration.resourceId(),
                                now);
                recordOutcome(
                        actor,
                        "administrative-elevation",
                        replay.id(),
                        auditAction,
                        AuditOutcome.SUCCESS,
                        now,
                        correlationId,
                        causationId);
                return replay;
            }

            AdministrativeElevation current =
                    elevations.get(actor, elevationId, now);
            AdministrativeElevation result =
                    command.apply(current);
            transactions.required(() -> {
                idempotency.complete(
                        actor.tenant(),
                        namespace,
                        key,
                        fingerprint,
                        "administrative-elevation",
                        result.id(),
                        now);
                return null;
            });
            recordOutcome(
                    actor,
                    "administrative-elevation",
                    result.id(),
                    auditAction,
                    AuditOutcome.SUCCESS,
                    now,
                    correlationId,
                    causationId);
            return result;
        } catch (RuntimeException failure) {
            recordOutcome(
                    actor,
                    "administrative-elevation",
                    elevationId,
                    auditAction,
                    auditOutcome(failure),
                    now,
                    correlationId,
                    causationId);
            throw failure;
        }
    }

    private <T> T atomic(
            AuthenticatedAdministrativeActor actor,
            String namespace,
            String resourceType,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId,
            UUID causationId,
            String auditAction,
            Supplier<T> command,
            Function<T, UUID> id,
            Function<UUID, T> replay) {
        try {
            T result = transactions.required(() -> {
                Registration registration =
                        idempotency.register(
                                actor.tenant(),
                                namespace,
                                key,
                                fingerprint,
                                now,
                                null);
                if (registration.kind()
                        == RegistrationKind.REPLAY) {
                    requireCompleted(
                            registration,
                            resourceType,
                            correlationId);
                    return replay.apply(
                            registration.resourceId());
                }
                T created = command.get();
                UUID resourceId = id.apply(created);
                idempotency.complete(
                        actor.tenant(),
                        namespace,
                        key,
                        fingerprint,
                        resourceType,
                        resourceId,
                        now);
                return created;
            });
            recordOutcome(
                    actor,
                    resourceType,
                    id.apply(result),
                    auditAction,
                    AuditOutcome.SUCCESS,
                    now,
                    correlationId,
                    causationId);
            return result;
        } catch (RuntimeException failure) {
            recordOutcome(
                    actor,
                    resourceType,
                    null,
                    auditAction,
                    auditOutcome(failure),
                    now,
                    correlationId,
                    causationId);
            throw failure;
        }
    }

    private AdministrativeBreakGlassOperation findBreakGlass(
            AuthenticatedAdministrativeActor actor,
            UUID operationId,
            Instant now) {
        try {
            return breakGlass.get(actor, operationId, now);
        } catch (AdministrativeAuthorityException notFound) {
            if ("administrative_break_glass_not_found"
                    .equals(notFound.code())) {
                return null;
            }
            throw notFound;
        }
    }

    private static void requireCompleted(
            Registration registration,
            String resourceType,
            UUID correlationId) {
        if (!"COMPLETED".equals(registration.operationState())) {
            throw AdministrationApiException.conflict(
                    correlationId,
                    "idempotency_in_progress",
                    "The same idempotency key is already being processed.");
        }
        if (!resourceType.equals(registration.resourceType())
                || registration.resourceId() == null) {
            throw new IllegalStateException(
                    "completed Administration idempotency result is invalid");
        }
    }

    private static AuditOutcome auditOutcome(
            RuntimeException failure) {
        if (failure instanceof AdministrativeAuthorityException authority
                && isDenied(authority.code())) {
            return AuditOutcome.DENIED;
        }
        if (failure instanceof ApprovalCommandException approval
                && "elevation_self_approval_forbidden"
                        .equals(approval.code())) {
            return AuditOutcome.DENIED;
        }
        if (failure instanceof AdministrationApiException api
                && api.status()
                        == org.springframework.http.HttpStatus.FORBIDDEN) {
            return AuditOutcome.DENIED;
        }
        return AuditOutcome.FAILURE;
    }

    private static boolean isDenied(String code) {
        return "administration_management_denied".equals(code)
                || code.endsWith("_not_eligible")
                || code.contains("_ceiling_exceeded")
                || code.endsWith("_denied")
                || code.endsWith("_required")
                || "active_temporary_authority_role_edit_denied".equals(code);
    }

    private void recordOutcome(
            AuthenticatedAdministrativeActor actor,
            String resourceType,
            UUID resourceId,
            String actionType,
            AuditOutcome outcome,
            Instant occurredAt,
            UUID correlationId,
            UUID causationId) {
        try {
            audit.append(
                    actor.tenant(),
                    new AuditRecordDraft(
                            ids.nextId(),
                            occurredAt,
                            actor.identityId(),
                            actionType,
                            resourceType,
                            resourceId,
                            outcome,
                            correlationId,
                            causationId));
        } catch (RuntimeException auditFailure) {
            LOG.warn(
                    "Administration AuditRecord append failed; correlationId={} actionType={} outcome={}",
                    correlationId,
                    actionType,
                    outcome);
        }
    }
}
