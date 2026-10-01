package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.administration.domain.AdministrativeElevation;
import io.wyrmgate.iam.administration.domain.AdministrativeElevationState;
import io.wyrmgate.iam.administration.domain.AdministrativeGrant;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.administration.domain.AdministrativeRole;
import io.wyrmgate.iam.administration.domain.AdministrativeScope;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class AdministrativeElevationService {
    private static final int MAX_PAGE_SIZE = 200;

    private final AdministrativeElevationRepository elevations;
    private final AdministrativeAuthorityRepository authority;
    private final AdministrativeAuthorizationService authorization;
    private final GovernedActorStatusQuery governedActors;
    private final AdministrativeElevationApprovalCommand approvals;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;

    public AdministrativeElevationService(
            AdministrativeElevationRepository elevations,
            AdministrativeAuthorityRepository authority,
            AdministrativeAuthorizationService authorization,
            GovernedActorStatusQuery governedActors,
            AdministrativeElevationApprovalCommand approvals,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this.elevations = Objects.requireNonNull(elevations, "elevations");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.governedActors = Objects.requireNonNull(governedActors, "governedActors");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public AdministrativeElevation request(
            AuthenticatedAdministrativeActor actor,
            UUID beneficiaryIdentityId,
            UUID roleId,
            AdministrativeScope scope,
            Instant validFrom,
            Instant validUntil,
            UUID authorityBasisGrantId,
            UUID correlationId,
            UUID causationId,
            Instant now) {
        Objects.requireNonNull(beneficiaryIdentityId, "beneficiaryIdentityId");
        Objects.requireNonNull(roleId, "roleId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(validUntil, "validUntil");
        Objects.requireNonNull(authorityBasisGrantId, "authorityBasisGrantId");
        requireManagement(actor, now);
        if (!governedActors.isAdministrativelyEligible(actor.tenant(), beneficiaryIdentityId)) {
            throw failure("beneficiary_not_eligible", "Elevation beneficiary is not an active governed Identity.");
        }
        if (!validUntil.isAfter(now) || (validFrom != null && !validUntil.isAfter(validFrom))) {
            throw failure("invalid_validity", "Elevation requires a finite future validity window.");
        }

        return transactions.required(() -> {
            AdministrativeRole role = authority.findRole(actor.tenant(), roleId)
                    .orElseThrow(() -> failure("administrative_role_not_found", "Administrative role does not exist."));
            AdministrativeGrant basis = authority.findGrant(actor.tenant(), authorityBasisGrantId)
                    .orElseThrow(() -> failure("authority_basis_not_found", "Elevation authority basis does not exist."));
            AdministrativeRole basisRole = authority.findRole(actor.tenant(), basis.roleId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Administrative grant references missing role " + basis.roleId()));
            requireBasis(actor, basis, basisRole.permissions(), role.permissions(), scope, validFrom, validUntil, now);
            String fingerprint = fingerprint(
                    beneficiaryIdentityId, actor.identityId(), basis, basisRole, role, scope, validFrom, validUntil);
            return elevations.insert(
                    actor.tenant(), ids.nextId(), beneficiaryIdentityId, actor.identityId(),
                    basis.id(), role.id(), scope, validFrom, validUntil, fingerprint,
                    correlationId, causationId, now);
        });
    }

    public AdministrativeElevation requestApproval(
            AuthenticatedAdministrativeActor actor,
            UUID elevationId,
            long expectedRevision,
            Instant now) {
        requireManagement(actor, now);
        AdministrativeElevation current = requireElevation(actor, elevationId);
        if (current.revision() != expectedRevision) {
            throw new io.wyrmgate.iam.platform.persistence.StaleWriteException(
                    "administrative-elevation", elevationId, expectedRevision);
        }
        if (current.state() != AdministrativeElevationState.REQUESTED) {
            throw failure("elevation_not_requestable_for_approval", "Elevation is not awaiting approval creation.");
        }

        // Cross-capability Governance call occurs outside the Administration transaction.
        var approval = approvals.requestApproval(
                actor.tenant(), current.id(), current.initiatorIdentityId(),
                current.beneficiaryIdentityId(), now);

        return transactions.required(() -> elevations.bindApproval(
                actor.tenant(), current.id(), expectedRevision,
                approval.approvalCaseId(), approval.planFingerprint(), now));
    }

    public AdministrativeElevation apply(
            AuthenticatedAdministrativeActor actor,
            UUID elevationId,
            long expectedRevision,
            Instant now) {
        requireManagement(actor, now);
        AdministrativeElevation current = requireElevation(actor, elevationId);
        if (current.revision() != expectedRevision) {
            throw new io.wyrmgate.iam.platform.persistence.StaleWriteException(
                    "administrative-elevation", elevationId, expectedRevision);
        }
        if (current.state() != AdministrativeElevationState.PENDING_APPROVAL) {
            throw failure("elevation_not_pending_approval", "Elevation is not pending approval.");
        }

        // Governance result is immutable evidence. It is queried outside the Administration transaction.
        var approval = approvals.currentApproval(actor.tenant(), elevationId);
        if (approval.outcome() == AdministrativeElevationApprovalCommand.Outcome.REJECTED) {
            return transactions.required(() -> elevations.transition(
                    actor.tenant(), elevationId, AdministrativeElevationState.PENDING_APPROVAL,
                    AdministrativeElevationState.DENIED, expectedRevision, now));
        }
        if (approval.outcome() != AdministrativeElevationApprovalCommand.Outcome.APPROVED) {
            throw failure("elevation_approval_not_satisfied", "Current approval has not approved this elevation.");
        }
        if (!Objects.equals(current.approvalCaseId(), approval.approvalCaseId())
                || !Objects.equals(current.approvalPlanFingerprint(), approval.planFingerprint())) {
            throw failure("stale_elevation_approval", "Approval evidence does not match the bound elevation context.");
        }

        return transactions.required(() -> {
            AdministrativeElevation locked = elevations.find(actor.tenant(), elevationId)
                    .orElseThrow(() -> failure("administrative_elevation_not_found", "Elevation does not exist."));
            if (locked.revision() != expectedRevision
                    || locked.state() != AdministrativeElevationState.PENDING_APPROVAL) {
                throw new io.wyrmgate.iam.platform.persistence.StaleWriteException(
                        "administrative-elevation", elevationId, expectedRevision);
            }
            if (!governedActors.isAdministrativelyEligible(actor.tenant(), locked.beneficiaryIdentityId())
                    || !governedActors.isAdministrativelyEligible(actor.tenant(), locked.initiatorIdentityId())) {
                throw failure("elevation_identity_not_eligible", "Elevation identities are no longer administratively eligible.");
            }
            AdministrativeRole role = authority.findRole(actor.tenant(), locked.roleId())
                    .orElseThrow(() -> failure("administrative_role_not_found", "Administrative role does not exist."));
            AdministrativeGrant basis = authority.findGrant(actor.tenant(), locked.authorityBasisGrantId())
                    .orElseThrow(() -> failure("authority_basis_not_found", "Elevation authority basis does not exist."));
            AdministrativeRole basisRole = authority.findRole(actor.tenant(), basis.roleId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Administrative grant references missing role " + basis.roleId()));
            requireBasis(
                    new AuthenticatedAdministrativeActor(actor.tenant(), locked.initiatorIdentityId()),
                    basis, basisRole.permissions(), role.permissions(),
                    locked.scope(), locked.validFrom(), locked.validUntil(), now);
            String fingerprint = fingerprint(
                    locked.beneficiaryIdentityId(), locked.initiatorIdentityId(),
                    basis, basisRole, role, locked.scope(), locked.validFrom(), locked.validUntil());
            if (!fingerprint.equals(locked.requestFingerprint())) {
                throw failure("stale_elevation_context", "Current authority context differs from the approved request.");
            }
            if (!locked.validUntil().isAfter(now)) {
                throw failure("elevation_expired_before_activation", "Elevation validity ended before activation.");
            }
            return elevations.transition(
                    actor.tenant(), elevationId, AdministrativeElevationState.PENDING_APPROVAL,
                    AdministrativeElevationState.ACTIVE, expectedRevision, now);
        });
    }

    public AdministrativeElevation cancel(
            AuthenticatedAdministrativeActor actor, UUID elevationId, long expectedRevision, Instant now) {
        requireManagement(actor, now);
        AdministrativeElevation current = requireElevation(actor, elevationId);
        if (current.state() != AdministrativeElevationState.REQUESTED
                && current.state() != AdministrativeElevationState.PENDING_APPROVAL) {
            throw failure("elevation_not_cancellable", "Only unactivated elevation may be cancelled.");
        }
        return transactions.required(() -> elevations.transition(
                actor.tenant(), elevationId, current.state(),
                AdministrativeElevationState.CANCELLED, expectedRevision, now));
    }

    public AdministrativeElevation revoke(
            AuthenticatedAdministrativeActor actor, UUID elevationId, long expectedRevision, Instant now) {
        requireManagement(actor, now);
        return transactions.required(() -> elevations.transition(
                actor.tenant(), elevationId, AdministrativeElevationState.ACTIVE,
                AdministrativeElevationState.REVOKED, expectedRevision, now));
    }

    public AdministrativeElevation get(
            AuthenticatedAdministrativeActor actor, UUID elevationId, Instant now) {
        requireManagement(actor, now);
        return requireElevation(actor, elevationId);
    }

    public List<AdministrativeElevation> list(
            AuthenticatedAdministrativeActor actor,
            Instant afterCreatedAt, UUID afterId, int limit, Instant now) {
        requireManagement(actor, now);
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE_SIZE);
        }
        return elevations.list(actor.tenant(), afterCreatedAt, afterId, limit);
    }

    private AdministrativeElevation requireElevation(
            AuthenticatedAdministrativeActor actor, UUID elevationId) {
        return elevations.find(actor.tenant(), Objects.requireNonNull(elevationId, "elevationId"))
                .orElseThrow(() -> failure("administrative_elevation_not_found", "Elevation does not exist."));
    }

    private void requireManagement(AuthenticatedAdministrativeActor actor, Instant now) {
        Objects.requireNonNull(actor, "actor");
        var decision = authorization.authorize(
                actor, AdministrativePermissions.MANAGE_AUTHORIZATION,
                AdministrativeResource.collection("administration"), now);
        if (!decision.allowed()) {
            throw failure("administration_management_denied", "Actor is not authorized to manage administrative authority.");
        }
    }

    private static void requireBasis(
            AuthenticatedAdministrativeActor actor,
            AdministrativeGrant basis,
            Set<AdministrativePermission> basisPermissions,
            Set<AdministrativePermission> targetPermissions,
            AdministrativeScope targetScope,
            Instant targetValidFrom,
            Instant targetValidUntil,
            Instant now) {
        if (!basis.actorIdentityId().equals(actor.identityId())
                || !basis.grantable()
                || !basis.isEffectiveAt(now)
                || !basisPermissions.containsAll(targetPermissions)
                || !basisContains(basis, targetScope, targetValidFrom, targetValidUntil, now)) {
            throw failure("elevation_ceiling_exceeded", "Requested elevation exceeds the current grantable basis.");
        }
    }

    private static boolean basisContains(
            AdministrativeGrant basis,
            AdministrativeScope targetScope,
            Instant targetValidFrom,
            Instant targetValidUntil,
            Instant now) {
        // The role permission containment is evaluated by the caller using the basis role.
        if (!AdministrativeAuthorityService.scopeContains(basis.scope(), targetScope)) return false;
        if (basis.validFrom() != null && targetValidFrom != null && targetValidFrom.isBefore(basis.validFrom())) return false;
        Instant effectiveStart = targetValidFrom == null || targetValidFrom.isBefore(now) ? now : targetValidFrom;
        if (basis.validUntil() != null && targetValidUntil.isAfter(basis.validUntil())) return false;
        return targetValidUntil.isAfter(effectiveStart);
    }

    private static String fingerprint(
            UUID beneficiaryIdentityId,
            UUID initiatorIdentityId,
            AdministrativeGrant basis,
            AdministrativeRole basisRole,
            AdministrativeRole role,
            AdministrativeScope scope,
            Instant validFrom,
            Instant validUntil) {
        String basisPermissions = basisRole.permissions().stream()
                .map(AdministrativePermission::key).sorted().reduce("", (a, b) -> a + b + ",");
        String targetPermissions = role.permissions().stream()
                .map(AdministrativePermission::key).sorted().reduce("", (a, b) -> a + b + ",");
        String canonical = beneficiaryIdentityId + "|" + initiatorIdentityId + "|"
                + basis.id() + "|" + basis.revision() + "|"
                + basisRole.id() + "|" + basisRole.revision() + "|" + basisPermissions + "|"
                + role.id() + "|" + role.revision() + "|" + targetPermissions + "|"
                + scope.type() + "|" + scope.resourceType() + "|" + scope.resourceId() + "|" + scope.scopeKey() + "|"
                + validFrom + "|" + validUntil;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static AdministrativeAuthorityException failure(String code, String message) {
        return new AdministrativeAuthorityException(code, message);
    }
}
