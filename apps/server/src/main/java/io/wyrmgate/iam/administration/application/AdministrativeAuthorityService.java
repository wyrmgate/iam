package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.administration.domain.AdministrativeDelegation;
import io.wyrmgate.iam.administration.domain.AdministrativeGrant;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.administration.domain.AdministrativeRole;
import io.wyrmgate.iam.administration.domain.AdministrativeScope;
import io.wyrmgate.iam.administration.domain.AdministrativeScopeType;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Administration-owned semantic command/query service for direct role/grant management and single-hop delegation.
 *
 * <p>Possessing manage-authorization permits the management operation but never substitutes for
 * the explicit grantability ceiling required by ADR-0032.</p>
 */
public final class AdministrativeAuthorityService {

    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_AUTHORITY_SCAN = 1_000;

    private final AdministrativeAuthorityRepository repository;
    private final AdministrativeAuthorizationService authorization;
    private final GovernedActorStatusQuery governedActorStatusQuery;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;

    public AdministrativeAuthorityService(
            AdministrativeAuthorityRepository repository,
            AdministrativeAuthorizationService authorization,
            GovernedActorStatusQuery governedActorStatusQuery,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.governedActorStatusQuery =
                Objects.requireNonNull(governedActorStatusQuery, "governedActorStatusQuery");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public AdministrativeRole createRole(
            AuthenticatedAdministrativeActor actor,
            String code,
            String name,
            Set<AdministrativePermission> permissions,
            Instant now) {
        Objects.requireNonNull(permissions, "permissions");
        requireManagement(actor, now);
        return transactions.required(() -> repository.createRole(
                actor.tenant(), ids.nextId(), code, name, Set.copyOf(permissions), now));
    }

    public AdministrativeRole getRole(
            AuthenticatedAdministrativeActor actor, UUID roleId, Instant now) {
        requireManagement(actor, now);
        return repository.findRole(actor.tenant(), Objects.requireNonNull(roleId, "roleId"))
                .orElseThrow(() -> failure("administrative_role_not_found", "Administrative role does not exist."));
    }

    public List<AdministrativeRole> listRoles(
            AuthenticatedAdministrativeActor actor,
            Instant afterCreatedAt,
            UUID afterId,
            int limit,
            Instant now) {
        requireManagement(actor, now);
        return repository.listRoles(actor.tenant(), afterCreatedAt, afterId, requirePageSize(limit));
    }

    public AdministrativeRole renameRole(
            AuthenticatedAdministrativeActor actor,
            UUID roleId,
            String name,
            long expectedRevision,
            Instant now) {
        requireManagement(actor, now);
        return transactions.required(() ->
                repository.renameRole(actor.tenant(), roleId, name, expectedRevision, now));
    }

    public AdministrativeRole addRolePermission(
            AuthenticatedAdministrativeActor actor,
            UUID roleId,
            AdministrativePermission permission,
            long expectedRevision,
            Instant now) {
        Objects.requireNonNull(permission, "permission");
        requireManagement(actor, now);
        return transactions.required(() -> {
            AdministrativeRole current = repository.findRole(actor.tenant(), roleId)
                    .orElseThrow(() -> failure(
                            "administrative_role_not_found", "Administrative role does not exist."));
            if (current.revision() != expectedRevision) {
                throw new io.wyrmgate.iam.platform.persistence.StaleWriteException(
                        "administrative-role", roleId, expectedRevision);
            }
            if (current.permissions().contains(permission)) {
                return current;
            }

            Set<AdministrativePermission> prospective = new LinkedHashSet<>(current.permissions());
            prospective.add(permission);
            requireProspectiveRoleIncreaseWithinGrantableAuthority(actor, roleId, prospective, now);
            return repository.addRolePermission(
                    actor.tenant(), roleId, permission, expectedRevision, now);
        });
    }

    public AdministrativeRole removeRolePermission(
            AuthenticatedAdministrativeActor actor,
            UUID roleId,
            AdministrativePermission permission,
            long expectedRevision,
            Instant now) {
        Objects.requireNonNull(permission, "permission");
        requireManagement(actor, now);
        return transactions.required(() -> {
            AdministrativeRole current = repository.findRole(actor.tenant(), roleId)
                    .orElseThrow(() -> failure(
                            "administrative_role_not_found", "Administrative role does not exist."));
            if (current.revision() != expectedRevision) {
                throw new io.wyrmgate.iam.platform.persistence.StaleWriteException(
                        "administrative-role", roleId, expectedRevision);
            }
            if (!current.permissions().contains(permission)) {
                return current;
            }
            if (current.permissions().size() == 1) {
                throw failure(
                        "administrative_role_requires_permission",
                        "Administrative role must retain at least one permission.");
            }
            // Permission removal is an authority reduction and intentionally skips
            // the privilege-increase grantability ceiling.
            return repository.removeRolePermission(
                    actor.tenant(), roleId, permission, expectedRevision, now);
        });
    }

    public AdministrativeGrant createGrant(
            AuthenticatedAdministrativeActor actor,
            UUID beneficiaryIdentityId,
            UUID roleId,
            AdministrativeScope scope,
            Instant validFrom,
            Instant validUntil,
            boolean grantable,
            boolean delegable,
            UUID authorityBasisGrantId,
            Instant now) {
        Objects.requireNonNull(beneficiaryIdentityId, "beneficiaryIdentityId");
        Objects.requireNonNull(roleId, "roleId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(authorityBasisGrantId, "authorityBasisGrantId");
        requireManagement(actor, now);
        if (!governedActorStatusQuery.isAdministrativelyEligible(actor.tenant(), beneficiaryIdentityId)) {
            throw failure(
                    "beneficiary_not_eligible",
                    "Administrative grant beneficiary is not an active governed Identity in this tenant.");
        }
        if (validUntil != null && !validUntil.isAfter(now)) {
            throw failure("invalid_validity", "Administrative grant validUntil must be in the future.");
        }

        return transactions.required(() -> {
            AdministrativeRole targetRole = repository.findRole(actor.tenant(), roleId)
                    .orElseThrow(() -> failure(
                            "administrative_role_not_found", "Administrative role does not exist."));
            AdministrativeGrant basis = repository.findGrant(actor.tenant(), authorityBasisGrantId)
                    .orElseThrow(() -> failure(
                            "authority_basis_not_found", "Administrative authority basis does not exist."));
            requireBasisContains(
                    actor,
                    basis,
                    targetRole.permissions(),
                    scope,
                    validFrom,
                    validUntil,
                    grantable,
                    delegable,
                    now);
            return repository.createGrant(
                    actor.tenant(),
                    ids.nextId(),
                    beneficiaryIdentityId,
                    roleId,
                    scope,
                    validFrom,
                    validUntil,
                    grantable,
                    delegable,
                    basis.id(),
                    now);
        });
    }

    public AdministrativeGrant getGrant(
            AuthenticatedAdministrativeActor actor, UUID grantId, Instant now) {
        requireManagement(actor, now);
        return repository.findGrant(actor.tenant(), Objects.requireNonNull(grantId, "grantId"))
                .orElseThrow(() -> failure(
                        "administrative_grant_not_found", "Administrative grant does not exist."));
    }

    public List<AdministrativeGrant> listGrants(
            AuthenticatedAdministrativeActor actor,
            Instant afterCreatedAt,
            UUID afterId,
            int limit,
            Instant now) {
        requireManagement(actor, now);
        return repository.listGrants(actor.tenant(), afterCreatedAt, afterId, requirePageSize(limit));
    }

    public AdministrativeGrant revokeGrant(
            AuthenticatedAdministrativeActor actor,
            UUID grantId,
            long expectedRevision,
            Instant now) {
        requireManagement(actor, now);
        // Revocation is an authority reduction. It deliberately does not require a
        // grantability basis and is not blocked by unrelated governance evaluation.
        return transactions.required(() ->
                repository.revokeGrant(actor.tenant(), grantId, expectedRevision, now));
    }

    public AdministrativeDelegation createDelegation(
            AuthenticatedAdministrativeActor actor,
            UUID delegateIdentityId,
            UUID sourceGrantId,
            AdministrativeScope scope,
            Instant validFrom,
            Instant validUntil,
            UUID correlationId,
            UUID causationId,
            Instant now) {
        Objects.requireNonNull(delegateIdentityId, "delegateIdentityId");
        Objects.requireNonNull(sourceGrantId, "sourceGrantId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(validUntil, "validUntil");
        requireManagement(actor, now);
        if (!governedActorStatusQuery.isAdministrativelyEligible(actor.tenant(), delegateIdentityId)) {
            throw failure(
                    "delegate_not_eligible",
                    "Administrative delegate is not an active governed Identity in this tenant.");
        }
        if (!validUntil.isAfter(now)) {
            throw failure("invalid_validity", "Administrative delegation validUntil must be in the future.");
        }
        if (validFrom != null && !validUntil.isAfter(validFrom)) {
            throw failure("invalid_validity", "Administrative delegation validUntil must be after validFrom.");
        }

        return transactions.required(() -> {
            AdministrativeGrant source = repository.findGrant(actor.tenant(), sourceGrantId)
                    .orElseThrow(() -> failure(
                            "delegation_source_not_found",
                            "Administrative delegation source grant does not exist."));
            if (!source.actorIdentityId().equals(actor.identityId())
                    || !source.delegable()
                    || !source.isEffectiveAt(now)) {
                throw failure(
                        "delegation_source_not_effective",
                        "Delegation source must be a current effective delegable direct grant held by the actor.");
            }
            if (!scopeContains(source.scope(), scope)
                    || !temporalContains(source, validFrom, validUntil, now)) {
                throw failure(
                        "delegation_ceiling_exceeded",
                        "Delegated scope or validity exceeds the current source grant.");
            }
            return repository.createDelegation(
                    actor.tenant(),
                    ids.nextId(),
                    delegateIdentityId,
                    actor.identityId(),
                    source.id(),
                    source.roleId(),
                    scope,
                    validFrom,
                    validUntil,
                    actor.identityId(),
                    correlationId,
                    causationId,
                    now);
        });
    }

    public AdministrativeDelegation getDelegation(
            AuthenticatedAdministrativeActor actor,
            UUID delegationId,
            Instant now) {
        requireManagement(actor, now);
        return repository.findDelegation(
                        actor.tenant(), Objects.requireNonNull(delegationId, "delegationId"))
                .orElseThrow(() -> failure(
                        "administrative_delegation_not_found",
                        "Administrative delegation does not exist."));
    }

    public List<AdministrativeDelegation> listDelegations(
            AuthenticatedAdministrativeActor actor,
            Instant afterCreatedAt,
            UUID afterId,
            int limit,
            Instant now) {
        requireManagement(actor, now);
        return repository.listDelegations(
                actor.tenant(), afterCreatedAt, afterId, requirePageSize(limit));
    }

    public AdministrativeDelegation revokeDelegation(
            AuthenticatedAdministrativeActor actor,
            UUID delegationId,
            long expectedRevision,
            Instant now) {
        requireManagement(actor, now);
        // Explicit delegation revocation is authoritative privilege reduction.
        return transactions.required(() -> repository.revokeDelegation(
                actor.tenant(),
                Objects.requireNonNull(delegationId, "delegationId"),
                expectedRevision,
                actor.identityId(),
                now));
    }

    private void requireProspectiveRoleIncreaseWithinGrantableAuthority(
            AuthenticatedAdministrativeActor actor,
            UUID roleId,
            Set<AdministrativePermission> prospectivePermissions,
            Instant now) {
        List<AdministrativeGrant> affected = repository.findAuthorityBearingGrantsByRole(
                actor.tenant(), roleId, now, MAX_AUTHORITY_SCAN + 1);
        List<AdministrativeDelegation> affectedDelegations =
                repository.findAuthorityBearingDelegationsByRole(
                        actor.tenant(), roleId, now, MAX_AUTHORITY_SCAN + 1);
        if (affected.size() > MAX_AUTHORITY_SCAN
                || affectedDelegations.size() > MAX_AUTHORITY_SCAN) {
            throw failure(
                    "authority_scan_limit_exceeded",
                    "Privilege-increasing role edit affects too much authority for the bounded synchronous check.");
        }
        if (affected.isEmpty() && affectedDelegations.isEmpty()) {
            return;
        }

        List<AdministrativeGrant> actorGrants = repository.findActorGrants(
                actor.tenant(), actor.identityId(), MAX_AUTHORITY_SCAN + 1);
        if (actorGrants.size() > MAX_AUTHORITY_SCAN) {
            throw failure(
                    "authority_scan_limit_exceeded",
                    "Actor has too many grants for the bounded synchronous grantability check.");
        }

        for (AdministrativeGrant target : affected) {
            requireContainedByActorGrantableAuthority(
                    actor, actorGrants, prospectivePermissions,
                    target.scope(), target.validFrom(), target.validUntil(),
                    target.grantable(), target.delegable(), now);
        }
        for (AdministrativeDelegation target : affectedDelegations) {
            requireContainedByActorGrantableAuthority(
                    actor, actorGrants, prospectivePermissions,
                    target.scope(), target.validFrom(), target.validUntil(),
                    false, false, now);
        }
    }

    private void requireContainedByActorGrantableAuthority(
            AuthenticatedAdministrativeActor actor,
            List<AdministrativeGrant> actorGrants,
            Set<AdministrativePermission> targetPermissions,
            AdministrativeScope targetScope,
            Instant targetValidFrom,
            Instant targetValidUntil,
            boolean targetGrantable,
            boolean targetDelegable,
            Instant now) {
        for (AdministrativeGrant candidate : actorGrants) {
            if (!candidate.grantable() || !candidate.isEffectiveAt(now)) {
                continue;
            }
            AdministrativeRole basisRole = repository.findRole(actor.tenant(), candidate.roleId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Administrative grant references missing role " + candidate.roleId()));
            if (contains(
                    candidate,
                    basisRole.permissions(),
                    targetPermissions,
                    targetScope,
                    targetValidFrom,
                    targetValidUntil,
                    targetGrantable,
                    targetDelegable,
                    now)) {
                return;
            }
        }
        throw failure(
                "grantability_ceiling_exceeded",
                "Role edit would increase existing administrative authority beyond the actor's current grantable authority.");
    }

    private void requireBasisContains(
            AuthenticatedAdministrativeActor actor,
            AdministrativeGrant basis,
            Set<AdministrativePermission> targetPermissions,
            AdministrativeScope targetScope,
            Instant targetValidFrom,
            Instant targetValidUntil,
            boolean targetGrantable,
            boolean targetDelegable,
            Instant now) {
        if (!basis.actorIdentityId().equals(actor.identityId())
                || !basis.grantable()
                || !basis.isEffectiveAt(now)) {
            throw failure(
                    "authority_basis_not_effective",
                    "Authority basis must be a current effective grantable direct grant held by the actor.");
        }
        AdministrativeRole basisRole = repository.findRole(actor.tenant(), basis.roleId())
                .orElseThrow(() -> new IllegalStateException(
                        "Administrative grant references missing role " + basis.roleId()));
        if (!contains(
                basis,
                basisRole.permissions(),
                targetPermissions,
                targetScope,
                targetValidFrom,
                targetValidUntil,
                targetGrantable,
                targetDelegable,
                now)) {
            throw failure(
                    "grantability_ceiling_exceeded",
                    "Requested administrative authority exceeds the selected current grantable basis.");
        }
    }

    private static boolean contains(
            AdministrativeGrant basis,
            Set<AdministrativePermission> basisPermissions,
            Set<AdministrativePermission> targetPermissions,
            AdministrativeScope targetScope,
            Instant targetValidFrom,
            Instant targetValidUntil,
            boolean targetGrantable,
            boolean targetDelegable,
            Instant now) {
        if (!basisPermissions.containsAll(targetPermissions)) {
            return false;
        }
        if (targetGrantable && !basis.grantable()) {
            return false;
        }
        if (targetDelegable && !basis.delegable()) {
            return false;
        }
        if (!scopeContains(basis.scope(), targetScope)) {
            return false;
        }

        if (basis.validFrom() != null
                && targetValidFrom != null
                && targetValidFrom.isBefore(basis.validFrom())) {
            return false;
        }
        Instant effectiveTargetStart =
                targetValidFrom == null || targetValidFrom.isBefore(now) ? now : targetValidFrom;
        if (basis.validUntil() != null) {
            if (targetValidUntil == null || targetValidUntil.isAfter(basis.validUntil())) {
                return false;
            }
        }
        return targetValidUntil == null || targetValidUntil.isAfter(effectiveTargetStart);
    }

    private static boolean temporalContains(
            AdministrativeGrant basis,
            Instant targetValidFrom,
            Instant targetValidUntil,
            Instant now) {
        if (basis.validFrom() != null
                && targetValidFrom != null
                && targetValidFrom.isBefore(basis.validFrom())) {
            return false;
        }
        Instant effectiveTargetStart =
                targetValidFrom == null || targetValidFrom.isBefore(now) ? now : targetValidFrom;
        if (basis.validUntil() != null && targetValidUntil.isAfter(basis.validUntil())) {
            return false;
        }
        return targetValidUntil.isAfter(effectiveTargetStart);
    }

    static boolean scopeContains(AdministrativeScope basis, AdministrativeScope target) {
        if (basis.type() == AdministrativeScopeType.GLOBAL) {
            return target.type() == AdministrativeScopeType.GLOBAL
                    || target.type() == AdministrativeScopeType.SPECIFIC_RESOURCE
                    || target.type() == AdministrativeScopeType.CANONICAL_ATTRIBUTE_CLASSIFICATION;
        }
        if (basis.type() == AdministrativeScopeType.SPECIFIC_RESOURCE
                && target.type() == AdministrativeScopeType.SPECIFIC_RESOURCE) {
            return Objects.equals(basis.resourceType(), target.resourceType())
                    && Objects.equals(basis.resourceId(), target.resourceId());
        }
        if (basis.type() == AdministrativeScopeType.CANONICAL_ATTRIBUTE_CLASSIFICATION
                && target.type() == AdministrativeScopeType.CANONICAL_ATTRIBUTE_CLASSIFICATION) {
            return Objects.equals(basis.scopeKey(), target.scopeKey());
        }
        return false;
    }

    private void requireManagement(AuthenticatedAdministrativeActor actor, Instant now) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(now, "now");
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

    private static int requirePageSize(int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE_SIZE);
        }
        return limit;
    }

    private static AdministrativeAuthorityException failure(String code, String message) {
        return new AdministrativeAuthorityException(code, message);
    }
}
