package io.wyrmgate.iam.administration.persistence;

import io.wyrmgate.iam.administration.application.GovernedActorStatusQuery;
import io.wyrmgate.iam.administration.application.TenantOwnerOverviewQuery;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Administration-owned read adapter for current Tenant Owner authority. */
public final class JdbcTenantOwnerOverviewQuery implements TenantOwnerOverviewQuery {
    private final JdbcTemplate jdbc;
    private final GovernedActorStatusQuery governedActors;

    public JdbcTenantOwnerOverviewQuery(JdbcTemplate jdbc, GovernedActorStatusQuery governedActors) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.governedActors = Objects.requireNonNull(governedActors, "governedActors");
    }

    @Override
    public Snapshot snapshot(TenantContext tenant, Instant at, int limit) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(at, "at");
        if (limit < 1 || limit > 20) {
            throw new IllegalArgumentException("tenant-owner overview limit must be between 1 and 20");
        }
        List<UUID> candidates = jdbc.query(
                """
                SELECT DISTINCT owner_authority.actor_identity_id
                FROM administration.tenant_owner_authority owner_authority
                JOIN administration.administrative_grant grant_authority
                  ON grant_authority.tenant_id = owner_authority.tenant_id
                 AND grant_authority.id = owner_authority.administrative_grant_id
                WHERE owner_authority.tenant_id = ?
                  AND owner_authority.state = 'ACTIVE'
                  AND grant_authority.state = 'ACTIVE'
                  AND (grant_authority.valid_from IS NULL OR grant_authority.valid_from <= ?)
                  AND (grant_authority.valid_until IS NULL OR grant_authority.valid_until > ?)
                ORDER BY owner_authority.actor_identity_id
                """,
                (rs, rowNum) -> rs.getObject("actor_identity_id", UUID.class),
                tenant.tenantId(), Timestamp.from(at), Timestamp.from(at));
        long viable = candidates.stream()
                .filter(identityId -> governedActors.isAdministrativelyEligible(tenant, identityId))
                .count();
        List<Owner> owners = candidates.stream()
                .limit(limit)
                .map(identityId -> new Owner(
                        identityId,
                        governedActors.isAdministrativelyEligible(tenant, identityId)))
                .toList();
        return new Snapshot(candidates.size(), viable, owners);
    }
}
