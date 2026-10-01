package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.administration.domain.AdministrativeBreakGlassOperation;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.administration.domain.AdministrativeRole;
import io.wyrmgate.iam.administration.domain.AdministrativeScope;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Administration-owned emergency-authority command/query service. */
public final class AdministrativeBreakGlassService {

    private static final int MAX_PAGE_SIZE = 200;

    private final AdministrativeBreakGlassRepository repository;
    private final AdministrativeAuthorityRepository authority;
    private final AdministrativeAuthorizationService authorization;
    private final GovernedActorStatusQuery governedActors;
    private final AdministrativeBreakGlassPolicy policy;
    private final AdministrativeBreakGlassAuditSink audit;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;

    public AdministrativeBreakGlassService(
            AdministrativeBreakGlassRepository repository,
            AdministrativeAuthorityRepository authority,
            AdministrativeAuthorizationService authorization,
            GovernedActorStatusQuery governedActors,
            AdministrativeBreakGlassPolicy policy,
            AdministrativeBreakGlassAuditSink audit,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.governedActors = Objects.requireNonNull(governedActors, "governedActors");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public AdministrativeBreakGlassOperation activate(
            AuthenticatedAdministrativeActor actor,
            UUID roleId,
            AdministrativeScope scope,
            Instant validUntil,
            String reason,
            String incidentReference,
            UUID correlationId,
            UUID causationId,
            Instant now) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(roleId, "roleId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(validUntil, "validUntil");
        Objects.requireNonNull(now, "now");
        UUID operationId = ids.nextId();
        try {
            AdministrativeBreakGlassOperation result = activateAuthoritative(
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
            auditSafely(
                    actor,
                    "administration.break-glass.activate",
                    result.id(),
                    AdministrativeBreakGlassAuditSink.Outcome.SUCCESS,
                    correlationId,
                    causationId,
                    now);
            return result;
        } catch (AdministrativeAuthorityException | IllegalArgumentException denied) {
            auditSafely(
                    actor,
                    "administration.break-glass.activate",
                    null,
                    isUnavailable(denied)
                            ? AdministrativeBreakGlassAuditSink.Outcome.FAILURE
                            : AdministrativeBreakGlassAuditSink.Outcome.DENIED,
                    correlationId,
                    causationId,
                    now);
            throw denied;
        } catch (RuntimeException failure) {
            auditSafely(
                    actor,
                    "administration.break-glass.activate",
                    null,
                    AdministrativeBreakGlassAuditSink.Outcome.FAILURE,
                    correlationId,
                    causationId,
                    now);
            throw failure;
        }
    }

    private AdministrativeBreakGlassOperation activateAuthoritative(
            UUID operationId,
            AuthenticatedAdministrativeActor actor,
            UUID roleId,
            AdministrativeScope scope,
            Instant validUntil,
            String reason,
            String incidentReference,
            UUID correlationId,
            UUID causationId,
            Instant now) {
        requireManagement(actor, now);
        String normalizedReason = bounded(reason, "reason", 2048);
        String normalizedIncident = bounded(incidentReference, "incidentReference", 512);
        if (!validUntil.isAfter(now)) {
            throw failure(
                    "break_glass_invalid_validity",
                    "Break-glass validUntil must be in the future.");
        }
        if (!governedActors.isAdministrativelyEligible(actor.tenant(), actor.identityId())) {
            throw failure(
                    "break_glass_actor_not_eligible",
                    "Break-glass actor is not an active governed Identity.");
        }

        AdministrativeRole role = authority.findRole(actor.tenant(), roleId)
                .orElseThrow(() -> failure(
                        "administrative_role_not_found",
                        "Administrative role does not exist."));

        AdministrativeBreakGlassPolicy.Decision decision;
        try {
            decision = policy.evaluate(
                    actor.tenant(),
                    actor.identityId(),
                    role,
                    scope,
                    validUntil,
                    now);
        } catch (RuntimeException unavailable) {
            throw failure(
                    "break_glass_policy_unavailable",
                    "Break-glass policy evaluation is unavailable.");
        }
        if (!decision.allowed()) {
            throw failure(
                    "break_glass_policy_denied",
                    "Break-glass policy denied the requested emergency authority.");
        }

        Duration requestedValidity = Duration.between(now, validUntil);
        if (requestedValidity.compareTo(decision.maxValidity()) > 0) {
            throw failure(
                    "break_glass_validity_exceeded",
                    "Requested break-glass validity exceeds the configured security-policy bound.");
        }
        if (!actor.assurance().satisfiesStrongAt(now, decision.maxAssuranceAge())) {
            throw failure(
                    "break_glass_strong_assurance_required",
                    "Current authentication assurance does not satisfy the required strong level.");
        }

        UUID notificationObligationId = ids.nextId();
        UUID reviewObligationId = ids.nextId();
        return transactions.required(() -> repository.activate(
                actor.tenant(),
                operationId,
                actor.identityId(),
                role,
                scope,
                normalizedReason,
                normalizedIncident,
                validUntil,
                actor.assurance(),
                decision.maxAssuranceAge(),
                notificationObligationId,
                reviewObligationId,
                correlationId,
                causationId,
                now));
    }

    public AdministrativeBreakGlassOperation revoke(
            AuthenticatedAdministrativeActor actor,
            UUID operationId,
            long expectedRevision,
            UUID correlationId,
            UUID causationId,
            Instant now) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(now, "now");
        try {
            requireManagement(actor, now);
            AdministrativeBreakGlassOperation result = transactions.required(() ->
                    repository.revoke(
                            actor.tenant(),
                            operationId,
                            actor.identityId(),
                            expectedRevision,
                            now));
            auditSafely(
                    actor,
                    "administration.break-glass.revoke",
                    operationId,
                    AdministrativeBreakGlassAuditSink.Outcome.SUCCESS,
                    correlationId,
                    causationId,
                    now);
            return result;
        } catch (AdministrativeAuthorityException denied) {
            auditSafely(
                    actor,
                    "administration.break-glass.revoke",
                    operationId,
                    AdministrativeBreakGlassAuditSink.Outcome.DENIED,
                    correlationId,
                    causationId,
                    now);
            throw denied;
        } catch (RuntimeException failure) {
            auditSafely(
                    actor,
                    "administration.break-glass.revoke",
                    operationId,
                    AdministrativeBreakGlassAuditSink.Outcome.FAILURE,
                    correlationId,
                    causationId,
                    now);
            throw failure;
        }
    }

    public AdministrativeBreakGlassOperation get(
            AuthenticatedAdministrativeActor actor,
            UUID operationId,
            Instant now) {
        requireManagement(actor, now);
        return repository.find(actor.tenant(), operationId)
                .orElseThrow(() -> failure(
                        "administrative_break_glass_not_found",
                        "Break-glass operation does not exist."));
    }

    public List<AdministrativeBreakGlassOperation> list(
            AuthenticatedAdministrativeActor actor,
            Instant afterCreatedAt,
            UUID afterId,
            int limit,
            Instant now) {
        requireManagement(actor, now);
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and " + MAX_PAGE_SIZE);
        }
        return repository.list(actor.tenant(), afterCreatedAt, afterId, limit);
    }

    private void requireManagement(
            AuthenticatedAdministrativeActor actor,
            Instant now) {
        var decision = authorization.authorize(
                actor,
                AdministrativePermissions.MANAGE_AUTHORIZATION,
                AdministrativeResource.collection("administration"),
                now);
        if (!decision.allowed()) {
            throw failure(
                    "administration_management_denied",
                    "Actor is not authorized to manage administrative authority.");
        }
    }

    private void auditSafely(
            AuthenticatedAdministrativeActor actor,
            String actionType,
            UUID resourceId,
            AdministrativeBreakGlassAuditSink.Outcome outcome,
            UUID correlationId,
            UUID causationId,
            Instant occurredAt) {
        try {
            audit.record(
                    actor.tenant(),
                    actor.identityId(),
                    actionType,
                    resourceId,
                    outcome,
                    correlationId,
                    causationId,
                    occurredAt);
        } catch (RuntimeException ignored) {
            // Audit failure must never rewrite an already-completed Administration result.
        }
    }

    private static boolean isUnavailable(RuntimeException exception) {
        return exception instanceof AdministrativeAuthorityException administrative
                && administrative.code().endsWith("_unavailable");
    }

    private static String bounded(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > max) {
            throw new IllegalArgumentException(
                    field + " must not exceed " + max + " characters");
        }
        return normalized;
    }

    private static AdministrativeAuthorityException failure(
            String code, String message) {
        return new AdministrativeAuthorityException(code, message);
    }
}
