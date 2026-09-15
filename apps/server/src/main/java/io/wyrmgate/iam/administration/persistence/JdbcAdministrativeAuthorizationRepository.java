package io.wyrmgate.iam.administration.persistence;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationRepository;
import io.wyrmgate.iam.administration.domain.AdministrativeGrant;
import io.wyrmgate.iam.administration.domain.AdministrativeGrantState;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativeScope;
import io.wyrmgate.iam.administration.domain.AdministrativeScopeType;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC adapter for Administration-owned direct grant evaluation. */
public final class JdbcAdministrativeAuthorizationRepository
        implements AdministrativeAuthorizationRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcAdministrativeAuthorizationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Override
    public List<AdministrativeGrant> findCandidateGrants(
            TenantContext tenant,
            UUID actorIdentityId,
            AdministrativePermission permission) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(actorIdentityId, "actorIdentityId");
        Objects.requireNonNull(permission, "permission");

        return jdbcTemplate.query(
                """
                SELECT g.id, g.actor_identity_id, g.role_id,
                       g.scope_type, g.scope_resource_type, g.scope_ref_id,
                       g.state, g.valid_from, g.valid_until,
                       g.revision, g.created_at, g.updated_at
                FROM administration.administrative_grant g
                JOIN administration.administrative_role_permission rp
                  ON rp.tenant_id = g.tenant_id AND rp.role_id = g.role_id
                JOIN administration.administrative_permission p
                  ON p.tenant_id = rp.tenant_id AND p.id = rp.permission_id
                WHERE g.tenant_id = ?
                  AND g.actor_identity_id = ?
                  AND p.resource_type = ?
                  AND p.action = ?
                ORDER BY g.created_at, g.id
                """,
                (rs, rowNum) -> new AdministrativeGrant(
                        rs.getObject("id", UUID.class),
                        rs.getObject("actor_identity_id", UUID.class),
                        rs.getObject("role_id", UUID.class),
                        new AdministrativeScope(
                                AdministrativeScopeType.valueOf(rs.getString("scope_type")),
                                rs.getString("scope_resource_type"),
                                rs.getObject("scope_ref_id", UUID.class)),
                        AdministrativeGrantState.valueOf(rs.getString("state")),
                        nullableInstant(rs.getTimestamp("valid_from")),
                        nullableInstant(rs.getTimestamp("valid_until")),
                        rs.getLong("revision"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                tenant.tenantId(),
                actorIdentityId,
                permission.resourceType(),
                permission.action());
    }

    private static java.time.Instant nullableInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
