package io.wyrmgate.iam.administration.persistence;

import io.wyrmgate.iam.administration.application.FirstPartyInitialAdminAnchorQuery;
import io.wyrmgate.iam.administration.application.GovernedActorStatusQuery;
import io.wyrmgate.iam.identity.application.IdentityAdministrativeContinuityGuard;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Administration-owned continuity evaluation exposed to Identity through a semantic guard port. */
public final class JdbcIdentityAdministrativeContinuityGuard
        implements IdentityAdministrativeContinuityGuard {

    private final JdbcTemplate jdbc;
    private final GovernedActorStatusQuery governedActors;
    private final FirstPartyInitialAdminAnchorQuery firstPartyAnchors;

    public JdbcIdentityAdministrativeContinuityGuard(
            JdbcTemplate jdbc,
            GovernedActorStatusQuery governedActors,
            FirstPartyInitialAdminAnchorQuery firstPartyAnchors) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.governedActors = Objects.requireNonNull(governedActors, "governedActors");
        this.firstPartyAnchors = Objects.requireNonNull(firstPartyAnchors, "firstPartyAnchors");
    }

    @Override
    public void assertMayBecomeAdministrativelyIneligible(
            TenantContext tenant,
            UUID identityId,
            Instant at) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(at, "at");

        if (isCurrentTenantOwner(tenant, identityId, at)
                && !hasOtherViableTenantOwner(tenant, identityId, at)) {
            throw new IllegalStateException("cannot disable the final viable TENANT_OWNER");
        }
        if (isCurrentPlatformOwner(tenant, identityId, at)
                && !hasOtherViablePlatformOwner(tenant, identityId, at)) {
            throw new IllegalStateException("cannot disable the final viable PLATFORM_OWNER");
        }
    }

    private boolean isCurrentTenantOwner(TenantContext tenant, UUID identityId, Instant at) {
        Integer count = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM administration.tenant_owner_authority owner_authority
                JOIN administration.administrative_grant grant_authority
                  ON grant_authority.tenant_id = owner_authority.tenant_id
                 AND grant_authority.id = owner_authority.administrative_grant_id
                WHERE owner_authority.tenant_id = ?
                  AND owner_authority.actor_identity_id = ?
                  AND owner_authority.state = 'ACTIVE'
                  AND grant_authority.state = 'ACTIVE'
                  AND (grant_authority.valid_from IS NULL OR grant_authority.valid_from <= ?)
                  AND (grant_authority.valid_until IS NULL OR grant_authority.valid_until > ?)
                """,
                Integer.class,
                tenant.tenantId(),
                identityId,
                Timestamp.from(at),
                Timestamp.from(at));
        return count != null && count > 0;
    }

    private boolean hasOtherViableTenantOwner(TenantContext tenant, UUID excludedIdentityId, Instant at) {
        List<UUID> candidates = jdbc.query(
                """
                SELECT DISTINCT owner_authority.actor_identity_id
                FROM administration.tenant_owner_authority owner_authority
                JOIN administration.administrative_grant grant_authority
                  ON grant_authority.tenant_id = owner_authority.tenant_id
                 AND grant_authority.id = owner_authority.administrative_grant_id
                WHERE owner_authority.tenant_id = ?
                  AND owner_authority.actor_identity_id <> ?
                  AND owner_authority.state = 'ACTIVE'
                  AND grant_authority.state = 'ACTIVE'
                  AND (grant_authority.valid_from IS NULL OR grant_authority.valid_from <= ?)
                  AND (grant_authority.valid_until IS NULL OR grant_authority.valid_until > ?)
                """,
                (rs, rowNum) -> rs.getObject("actor_identity_id", UUID.class),
                tenant.tenantId(),
                excludedIdentityId,
                Timestamp.from(at),
                Timestamp.from(at));
        return candidates.stream().anyMatch(candidate ->
                governedActors.isAdministrativelyEligible(tenant, candidate));
    }

    private boolean isCurrentPlatformOwner(TenantContext tenant, UUID identityId, Instant at) {
        Integer count = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM administration.platform_owner_authority
                WHERE actor_tenant_id = ?
                  AND actor_identity_id = ?
                  AND state = 'ACTIVE'
                  AND (valid_from IS NULL OR valid_from <= ?)
                  AND (valid_until IS NULL OR valid_until > ?)
                """,
                Integer.class,
                tenant.tenantId(),
                identityId,
                Timestamp.from(at),
                Timestamp.from(at));
        return count != null && count > 0;
    }

    private boolean hasOtherViablePlatformOwner(
            TenantContext excludedTenant,
            UUID excludedIdentityId,
            Instant at) {
        List<PlatformOwnerCandidate> candidates = jdbc.query(
                """
                SELECT actor_tenant_id, actor_identity_id, actor_principal_id
                FROM administration.platform_owner_authority
                WHERE state = 'ACTIVE'
                  AND NOT (actor_tenant_id = ? AND actor_identity_id = ?)
                  AND (valid_from IS NULL OR valid_from <= ?)
                  AND (valid_until IS NULL OR valid_until > ?)
                """,
                (rs, rowNum) -> new PlatformOwnerCandidate(
                        rs.getObject("actor_tenant_id", UUID.class),
                        rs.getObject("actor_identity_id", UUID.class),
                        rs.getObject("actor_principal_id", UUID.class)),
                excludedTenant.tenantId(),
                excludedIdentityId,
                Timestamp.from(at),
                Timestamp.from(at));
        return candidates.stream().anyMatch(this::isViablePlatformOwner);
    }

    private boolean isViablePlatformOwner(PlatformOwnerCandidate candidate) {
        TenantContext actorTenant = new TenantContext(candidate.tenantId());
        if (!governedActors.isAdministrativelyEligible(actorTenant, candidate.identityId())) {
            return false;
        }
        return candidate.principalId() == null
                || firstPartyAnchors.isEligiblePrincipalForIdentity(
                        actorTenant, candidate.principalId(), candidate.identityId());
    }

    private record PlatformOwnerCandidate(UUID tenantId, UUID identityId, UUID principalId) {
    }
}
