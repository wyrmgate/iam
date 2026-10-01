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
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.Registration;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.RegistrationKind;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class AdministrationApiMutationService {
    private static final Logger LOG = LoggerFactory.getLogger(AdministrationApiMutationService.class);

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
        this.authority = authority;
        this.elevations = elevations;
        this.breakGlass = breakGlass;
        this.idempotency = idempotency;
        this.transactions = transactions;
        this.audit = audit;
        this.ids = ids;
    }

    AdministrativeRole createRole(
            AuthenticatedAdministrativeActor actor, String code, String name,
            Set<AdministrativePermission> permissions, String key,
            RequestFingerprint fingerprint, Instant now, UUID correlationId) {
        return audited(actor, "administrative-role", null, "administration.role.create", now, correlationId,
                () -> transactions.required(() -> {
                    Registration r = register(actor, "api.administration.role.create.v1", key, fingerprint, now);
                    if (r.kind() == RegistrationKind.REPLAY) return replayRole(actor, r, now, correlationId);
                    AdministrativeRole value = authority.createRole(actor, code, name, permissions, now);
                    complete(actor, "api.administration.role.create.v1", key, fingerprint,
                            "administrative-role", value.id(), now);
                    return value;
                }), AdministrativeRole::id);
    }

    AdministrativeRole renameRole(
            AuthenticatedAdministrativeActor actor, UUID id, String name, long revision,
            String key, RequestFingerprint fingerprint, Instant now, UUID correlationId) {
        return audited(actor, "administrative-role", id, "administration.role.rename", now, correlationId,
                () -> transactions.required(() -> {
                    Registration r = register(actor, "api.administration.role.rename.v1", key, fingerprint, now);
                    if (r.kind() == RegistrationKind.REPLAY) return replayRole(actor, r, now, correlationId);
                    AdministrativeRole value = authority.renameRole(actor, id, name, revision, now);
                    complete(actor, "api.administration.role.rename.v1", key, fingerprint,
                            "administrative-role", value.id(), now);
                    return value;
                }), AdministrativeRole::id);
    }

    AdministrativeRole addPermission(
            AuthenticatedAdministrativeActor actor, UUID id, AdministrativePermission permission,
            long revision, String key, RequestFingerprint fingerprint, Instant now, UUID correlationId) {
        return audited(actor, "administrative-role", id, "administration.role.permission.add", now, correlationId,
                () -> transactions.required(() -> {
                    Registration r = register(actor, "api.administration.role.permission.add.v1", key, fingerprint, now);
                    if (r.kind() == RegistrationKind.REPLAY) return replayRole(actor, r, now, correlationId);
                    AdministrativeRole value = authority.addRolePermission(actor, id, permission, revision, now);
                    complete(actor, "api.administration.role.permission.add.v1", key, fingerprint,
                            "administrative-role", value.id(), now);
                    return value;
                }), AdministrativeRole::id);
    }

    AdministrativeRole removePermission(
            AuthenticatedAdministrativeActor actor, UUID id, AdministrativePermission permission,
            long revision, String key, RequestFingerprint fingerprint, Instant now, UUID correlationId) {
        return audited(actor, "administrative-role", id, "administration.role.permission.remove", now, correlationId,
                () -> transactions.required(() -> {
                    Registration r = register(actor, "api.administration.role.permission.remove.v1", key, fingerprint, now);
                    if (r.kind() == RegistrationKind.REPLAY) return replayRole(actor, r, now, correlationId);
                    AdministrativeRole value = authority.removeRolePermission(actor, id, permission, revision, now);
                    complete(actor, "api.administration.role.permission.remove.v1", key, fingerprint,
                            "administrative-role", value.id(), now);
                    return value;
                }), AdministrativeRole::id);
    }

    AdministrativeGrant createGrant(
            AuthenticatedAdministrativeActor actor, UUID beneficiaryId, UUID roleId, AdministrativeScope scope,
            Instant validFrom, Instant validUntil, boolean grantable, boolean delegable, UUID basisId,
            String key, RequestFingerprint fingerprint, Instant now, UUID correlationId) {
        return audited(actor, "administrative-grant", null, "administration.grant.create", now, correlationId,
                () -> transactions.required(() -> {
                    Registration r = register(actor, "api.administration.grant.create.v1", key, fingerprint, now);
                    if (r.kind() == RegistrationKind.REPLAY) return replayGrant(actor, r, now, correlationId);
                    AdministrativeGrant value = authority.createGrant(
                            actor, beneficiaryId, roleId, scope, validFrom, validUntil,
                            grantable, delegable, basisId, now);
                    complete(actor, "api.administration.grant.create.v1", key, fingerprint,
                            "administrative-grant", value.id(), now);
                    return value;
                }), AdministrativeGrant::id);
    }

    AdministrativeGrant revokeGrant(
            AuthenticatedAdministrativeActor actor, UUID id, long revision,
            String key, RequestFingerprint fingerprint, Instant now, UUID correlationId) {
        return audited(actor, "administrative-grant", id, "administration.grant.revoke", now, correlationId,
                () -> transactions.required(() -> {
                    Registration r = register(actor, "api.administration.grant.revoke.v1", key, fingerprint, now);
                    if (r.kind() == RegistrationKind.REPLAY) return replayGrant(actor, r, now, correlationId);
                    AdministrativeGrant value = authority.revokeGrant(actor, id, revision, now);
                    complete(actor, "api.administration.grant.revoke.v1", key, fingerprint,
                            "administrative-grant", value.id(), now);
                    return value;
                }), AdministrativeGrant::id);
    }

    AdministrativeDelegation createDelegation(
            AuthenticatedAdministrativeActor actor, UUID delegateId, UUID sourceGrantId,
            AdministrativeScope scope, Instant validFrom, Instant validUntil,
            String key, RequestFingerprint fingerprint, Instant now, UUID correlationId) {
        return audited(actor, "administrative-delegation", null, "administration.delegation.create", now, correlationId,
                () -> transactions.required(() -> {
                    Registration r = register(actor, "api.administration.delegation.create.v1", key, fingerprint, now);
                    if (r.kind() == RegistrationKind.REPLAY) return replayDelegation(actor, r, now, correlationId);
                    AdministrativeDelegation value = authority.createDelegation(
                            actor, delegateId, sourceGrantId, scope, validFrom, validUntil,
                            correlationId, null, now);
                    complete(actor, "api.administration.delegation.create.v1", key, fingerprint,
                            "administrative-delegation", value.id(), now);
                    return value;
                }), AdministrativeDelegation::id);
    }

    AdministrativeDelegation revokeDelegation(
            AuthenticatedAdministrativeActor actor, UUID id, long revision,
            String key, RequestFingerprint fingerprint, Instant now, UUID correlationId) {
        return audited(actor, "administrative-delegation", id, "administration.delegation.revoke", now, correlationId,
                () -> transactions.required(() -> {
                    Registration r = register(actor, "api.administration.delegation.revoke.v1", key, fingerprint, now);
                    if (r.kind() == RegistrationKind.REPLAY) return replayDelegation(actor, r, now, correlationId);
                    AdministrativeDelegation value = authority.revokeDelegation(actor, id, revision, now);
                    complete(actor, "api.administration.delegation.revoke.v1", key, fingerprint,
                            "administrative-delegation", value.id(), now);
                    return value;
                }), AdministrativeDelegation::id);
    }

    AdministrativeElevation requestElevation(
            AuthenticatedAdministrativeActor actor, UUID beneficiaryId, UUID roleId, AdministrativeScope scope,
            Instant validFrom, Instant validUntil, UUID basisId,
            String key, RequestFingerprint fingerprint, Instant now, UUID correlationId) {
        return audited(actor, "administrative-elevation", null, "administration.elevation.request", now, correlationId,
                () -> transactions.required(() -> {
                    Registration r = register(actor, "api.administration.elevation.request.v1", key, fingerprint, now);
                    if (r.kind() == RegistrationKind.REPLAY) return replayElevation(actor, r, now, correlationId);
                    AdministrativeElevation value = elevations.request(
                            actor, beneficiaryId, roleId, scope, validFrom, validUntil,
                            basisId, correlationId, null, now);
                    complete(actor, "api.administration.elevation.request.v1", key, fingerprint,
                            "administrative-elevation", value.id(), now);
                    return value;
                }), AdministrativeElevation::id);
    }

    AdministrativeElevation requestElevationApproval(
            AuthenticatedAdministrativeActor actor, UUID id, long revision,
            String key, RequestFingerprint fingerprint, Instant now, UUID correlationId) {
        return crossCapabilityElevation(
                actor, id, revision, key, fingerprint, now, correlationId,
                "api.administration.elevation.request-approval.v1",
                "administration.elevation.request-approval",
                AdministrativeElevationState.PENDING_APPROVAL,
                () -> elevations.requestApproval(actor, id, revision, now));
    }

    AdministrativeElevation applyElevation(
            AuthenticatedAdministrativeActor actor, UUID id, long revision,
            String key, RequestFingerprint fingerprint, Instant now, UUID correlationId) {
        return crossCapabilityElevation(
                actor, id, revision, key, fingerprint, now, correlationId,
                "api.administration.elevation.apply.v1",
                "administration.elevation.apply",
                null,
                () -> elevations.apply(actor, id, revision, now));
    }

    private AdministrativeElevation crossCapabilityElevation(
            AuthenticatedAdministrativeActor actor, UUID id, long revision, String key,
            RequestFingerprint fingerprint, Instant now, UUID correlationId,
            String namespace, String actionType, AdministrativeElevationState expectedSuccessState,
            Supplier<AdministrativeElevation> operation) {
        return audited(actor, "administrative-elevation", id, actionType, now, correlationId, () -> {
            Registration r = transactions.required(() -> register(actor, namespace, key, fingerprint, now));
            if (r.kind() == RegistrationKind.REPLAY && "COMPLETED".equals(r.operationState())) {
                return replayElevation(actor, r, now, correlationId);
            }
            if (r.kind() == RegistrationKind.REPLAY) {
                AdministrativeElevation current = elevations.get(actor, id, now);
                if (expectedSuccessState != null && current.state() == expectedSuccessState) {
                    transactions.required(() -> {
                        complete(actor, namespace, key, fingerprint, "administrative-elevation", current.id(), now);
                        return null;
                    });
                    return current;
                }
                if (expectedSuccessState == null
                        && (current.state() == AdministrativeElevationState.ACTIVE
                        || current.state() == AdministrativeElevationState.DENIED)) {
                    transactions.required(() -> {
                        complete(actor, namespace, key, fingerprint, "administrative-elevation", current.id(), now);
                        return null;
                    });
                    return current;
                }
            }
            AdministrativeElevation value = operation.get();
            transactions.required(() -> {
                complete(actor, namespace, key, fingerprint, "administrative-elevation", value.id(), now);
                return null;
            });
            return value;
        }, AdministrativeElevation::id);
    }

    AdministrativeElevation cancelElevation(
            AuthenticatedAdministrativeActor actor, UUID id, long revision,
            String key, RequestFingerprint fingerprint, Instant now, UUID correlationId) {
        return audited(actor, "administrative-elevation", id, "administration.elevation.cancel", now, correlationId,
                () -> transactions.required(() -> {
                    Registration r = register(actor, "api.administration.elevation.cancel.v1", key, fingerprint, now);
                    if (r.kind() == RegistrationKind.REPLAY) return replayElevation(actor, r, now, correlationId);
                    AdministrativeElevation value = elevations.cancel(actor, id, revision, now);
                    complete(actor, "api.administration.elevation.cancel.v1", key, fingerprint,
                            "administrative-elevation", value.id(), now);
                    return value;
                }), AdministrativeElevation::id);
    }

    AdministrativeElevation revokeElevation(
            AuthenticatedAdministrativeActor actor, UUID id, long revision,
            String key, RequestFingerprint fingerprint, Instant now, UUID correlationId) {
        return audited(actor, "administrative-elevation", id, "administration.elevation.revoke", now, correlationId,
                () -> transactions.required(() -> {
                    Registration r = register(actor, "api.administration.elevation.revoke.v1", key, fingerprint, now);
                    if (r.kind() == RegistrationKind.REPLAY) return replayElevation(actor, r, now, correlationId);
                    AdministrativeElevation value = elevations.revoke(actor, id, revision, now);
                    complete(actor, "api.administration.elevation.revoke.v1", key, fingerprint,
                            "administrative-elevation", value.id(), now);
                    return value;
                }), AdministrativeElevation::id);
    }

    AdministrativeBreakGlassOperation activateBreakGlass(
            AuthenticatedAdministrativeActor actor, UUID roleId, AdministrativeScope scope, Instant validUntil,
            String reason, String incidentReference, String key, RequestFingerprint fingerprint,
            Instant now, UUID correlationId) {
        String namespace = "api.administration.break-glass.activate.v1";
        Registration r = transactions.required(() -> register(actor, namespace, key, fingerprint, now));
        UUID operationId = deterministicId(actor, namespace, key);
        if (r.kind() == RegistrationKind.REPLAY && "COMPLETED".equals(r.operationState())) {
            return replayBreakGlass(actor, r, now, correlationId);
        }
        if (r.kind() == RegistrationKind.REPLAY) {
            try {
                AdministrativeBreakGlassOperation existing = breakGlass.get(actor, operationId, now);
                transactions.required(() -> {
                    complete(actor, namespace, key, fingerprint, "administrative-break-glass", existing.id(), now);
                    return null;
                });
                return existing;
            } catch (AdministrativeAuthorityException notFound) {
                if (!"administrative_break_glass_not_found".equals(notFound.code())) throw notFound;
            }
        }
        AdministrativeBreakGlassOperation value = breakGlass.activate(
                operationId, actor, roleId, scope, validUntil, reason, incidentReference,
                correlationId, null, now);
        transactions.required(() -> {
            complete(actor, namespace, key, fingerprint, "administrative-break-glass", value.id(), now);
            return null;
        });
        return value;
    }

    AdministrativeBreakGlassOperation revokeBreakGlass(
            AuthenticatedAdministrativeActor actor, UUID id, long revision,
            String key, RequestFingerprint fingerprint, Instant now, UUID correlationId) {
        String namespace = "api.administration.break-glass.revoke.v1";
        Registration r = transactions.required(() -> register(actor, namespace, key, fingerprint, now));
        if (r.kind() == RegistrationKind.REPLAY && "COMPLETED".equals(r.operationState())) {
            return replayBreakGlass(actor, r, now, correlationId);
        }
        if (r.kind() == RegistrationKind.REPLAY) {
            AdministrativeBreakGlassOperation current = breakGlass.get(actor, id, now);
            if (current.state() == AdministrativeBreakGlassState.REVOKED) {
                transactions.required(() -> {
                    complete(actor, namespace, key, fingerprint, "administrative-break-glass", current.id(), now);
                    return null;
                });
                return current;
            }
        }
        AdministrativeBreakGlassOperation value =
                breakGlass.revoke(actor, id, revision, correlationId, null, now);
        transactions.required(() -> {
            complete(actor, namespace, key, fingerprint, "administrative-break-glass", value.id(), now);
            return null;
        });
        return value;
    }

    private <T> T audited(
            AuthenticatedAdministrativeActor actor, String resourceType, UUID resourceId,
            String actionType, Instant now, UUID correlationId,
            Supplier<T> operation, Function<T, UUID> resultId) {
        try {
            T result = operation.get();
            record(actor, resourceType, resourceId != null ? resourceId : resultId.apply(result),
                    actionType, AuditOutcome.SUCCESS, now, correlationId);
            return result;
        } catch (RuntimeException failure) {
            record(actor, resourceType, resourceId, actionType, outcome(failure), now, correlationId);
            throw failure;
        }
    }

    private static AuditOutcome outcome(RuntimeException failure) {
        if (failure instanceof AdministrativeAuthorityException administrative
                && !administrative.code().endsWith("_unavailable")) {
            return AuditOutcome.DENIED;
        }
        if (failure instanceof AdministrationApiException api
                && api.status() == org.springframework.http.HttpStatus.FORBIDDEN) {
            return AuditOutcome.DENIED;
        }
        return AuditOutcome.FAILURE;
    }

    private void record(
            AuthenticatedAdministrativeActor actor, String resourceType, UUID resourceId,
            String actionType, AuditOutcome outcome, Instant now, UUID correlationId) {
        try {
            audit.append(actor.tenant(), new AuditRecordDraft(
                    ids.nextId(), now, actor.identityId(), actionType, resourceType,
                    resourceId, outcome, correlationId, null));
        } catch (RuntimeException ignored) {
            LOG.warn("Administration AuditRecord append failed; correlationId={} actionType={} outcome={}",
                    correlationId, actionType, outcome);
        }
    }

    private Registration register(
            AuthenticatedAdministrativeActor actor, String namespace, String key,
            RequestFingerprint fingerprint, Instant now) {
        return idempotency.register(actor.tenant(), namespace, key, fingerprint, now, null);
    }

    private void complete(
            AuthenticatedAdministrativeActor actor, String namespace, String key,
            RequestFingerprint fingerprint, String resourceType, UUID resourceId, Instant now) {
        idempotency.complete(actor.tenant(), namespace, key, fingerprint, resourceType, resourceId, now);
    }

    private AdministrativeRole replayRole(
            AuthenticatedAdministrativeActor actor, Registration r, Instant now, UUID correlationId) {
        requireCompleted(r, "administrative-role", correlationId);
        return authority.getRole(actor, r.resourceId(), now);
    }

    private AdministrativeGrant replayGrant(
            AuthenticatedAdministrativeActor actor, Registration r, Instant now, UUID correlationId) {
        requireCompleted(r, "administrative-grant", correlationId);
        return authority.getGrant(actor, r.resourceId(), now);
    }

    private AdministrativeDelegation replayDelegation(
            AuthenticatedAdministrativeActor actor, Registration r, Instant now, UUID correlationId) {
        requireCompleted(r, "administrative-delegation", correlationId);
        return authority.getDelegation(actor, r.resourceId(), now);
    }

    private AdministrativeElevation replayElevation(
            AuthenticatedAdministrativeActor actor, Registration r, Instant now, UUID correlationId) {
        requireCompleted(r, "administrative-elevation", correlationId);
        return elevations.get(actor, r.resourceId(), now);
    }

    private AdministrativeBreakGlassOperation replayBreakGlass(
            AuthenticatedAdministrativeActor actor, Registration r, Instant now, UUID correlationId) {
        requireCompleted(r, "administrative-break-glass", correlationId);
        return breakGlass.get(actor, r.resourceId(), now);
    }

    private static void requireCompleted(Registration r, String resourceType, UUID correlationId) {
        if (!"COMPLETED".equals(r.operationState())) {
            throw AdministrationApiException.conflict(
                    correlationId, "idempotency_in_progress",
                    "The same idempotency key is already being processed.");
        }
        if (!resourceType.equals(r.resourceType()) || r.resourceId() == null) {
            throw new IllegalStateException("completed Administration idempotency result is invalid");
        }
    }

    private static UUID deterministicId(
            AuthenticatedAdministrativeActor actor, String namespace, String key) {
        String canonical = actor.tenant().tenantId() + "|" + namespace + "|" + key;
        return UUID.nameUUIDFromBytes(canonical.getBytes(StandardCharsets.UTF_8));
    }
}
