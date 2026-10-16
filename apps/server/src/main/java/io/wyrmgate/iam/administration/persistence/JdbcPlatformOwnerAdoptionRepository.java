package io.wyrmgate.iam.administration.persistence;

import io.wyrmgate.iam.administration.application.PlatformOwnerAdoptionRepository;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC evidence queries for the explicit ADR-0049 legacy ownership adoption operation. */
public final class JdbcPlatformOwnerAdoptionRepository implements PlatformOwnerAdoptionRepository {

    private final JdbcTemplate jdbc;

    public JdbcPlatformOwnerAdoptionRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public long countPlatformOwnerAuthorities() {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM administration.platform_owner_authority",
                Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public long countMatchingActivePlatformOwner(
            TenantContext tenant,
            UUID identityId,
            UUID principalId) {
        Long count = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM administration.platform_owner_authority
                WHERE actor_tenant_id = ?
                  AND actor_identity_id = ?
                  AND actor_principal_id = ?
                  AND state = 'ACTIVE'
                """,
                Long.class,
                tenant.tenantId(),
                identityId,
                principalId);
        return count == null ? 0 : count;
    }

    @Override
    public long countTenantOwnerAuthorities(TenantContext tenant) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM administration.tenant_owner_authority WHERE tenant_id = ?",
                Long.class,
                tenant.tenantId());
        return count == null ? 0 : count;
    }

    @Override
    public long countMatchingActiveTenantOwner(
            TenantContext tenant,
            UUID identityId,
            UUID administrativeGrantId) {
        Long count = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM administration.tenant_owner_authority
                WHERE tenant_id = ?
                  AND actor_identity_id = ?
                  AND administrative_grant_id = ?
                  AND state = 'ACTIVE'
                """,
                Long.class,
                tenant.tenantId(),
                identityId,
                administrativeGrantId);
        return count == null ? 0 : count;
    }
}
