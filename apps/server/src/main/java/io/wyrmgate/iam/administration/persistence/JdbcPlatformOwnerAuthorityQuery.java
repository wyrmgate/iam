package io.wyrmgate.iam.administration.persistence;

import io.wyrmgate.iam.administration.application.FirstPartyInitialAdminAnchorQuery;
import io.wyrmgate.iam.administration.application.GovernedActorStatusQuery;
import io.wyrmgate.iam.administration.application.PlatformOwnerAuthorityQuery;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC-backed Administration query for current viable deployment owners. */
public final class JdbcPlatformOwnerAuthorityQuery implements PlatformOwnerAuthorityQuery {

    private final JdbcTemplate jdbc;
    private final GovernedActorStatusQuery governedActors;
    private final FirstPartyInitialAdminAnchorQuery firstPartyAnchors;

    public JdbcPlatformOwnerAuthorityQuery(
            JdbcTemplate jdbc,
            GovernedActorStatusQuery governedActors,
            FirstPartyInitialAdminAnchorQuery firstPartyAnchors) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.governedActors = Objects.requireNonNull(governedActors, "governedActors");
        this.firstPartyAnchors = Objects.requireNonNull(firstPartyAnchors, "firstPartyAnchors");
    }

    @Override
    public boolean isViablePlatformOwner(
            TenantContext actorTenant,
            UUID actorIdentityId,
            Instant at) {
        Objects.requireNonNull(actorTenant, "actorTenant");
        Objects.requireNonNull(actorIdentityId, "actorIdentityId");
        Objects.requireNonNull(at, "at");
        if (!governedActors.isAdministrativelyEligible(actorTenant, actorIdentityId)) {
            return false;
        }

        List<UUID> principalIds = jdbc.query(
                """
                SELECT actor_principal_id
                FROM administration.platform_owner_authority
                WHERE actor_tenant_id = ?
                  AND actor_identity_id = ?
                  AND state = 'ACTIVE'
                  AND (valid_from IS NULL OR valid_from <= ?)
                  AND (valid_until IS NULL OR valid_until > ?)
                """,
                (rs, rowNum) -> rs.getObject("actor_principal_id", UUID.class),
                actorTenant.tenantId(),
                actorIdentityId,
                Timestamp.from(at),
                Timestamp.from(at));
        return principalIds.stream().anyMatch(principalId -> principalId == null
                || firstPartyAnchors.isEligiblePrincipalForIdentity(
                        actorTenant, principalId, actorIdentityId));
    }
}
