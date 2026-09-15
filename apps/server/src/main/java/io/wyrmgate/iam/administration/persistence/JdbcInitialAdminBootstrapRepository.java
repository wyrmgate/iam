package io.wyrmgate.iam.administration.persistence;

import io.wyrmgate.iam.administration.application.InitialAdminBootstrapRepository;
import io.wyrmgate.iam.administration.domain.AdministrativeGrant;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativeRole;
import io.wyrmgate.iam.administration.domain.InitialAdminBootstrap;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC mutation adapter for the burn-once first administrative bootstrap transaction. */
public final class JdbcInitialAdminBootstrapRepository implements InitialAdminBootstrapRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcInitialAdminBootstrapRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Override
    public boolean hasAnyAdministrativeGrant(TenantContext tenant) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM administration.administrative_grant WHERE tenant_id = ?",
                Integer.class,
                tenant.tenantId());
        return count != null && count > 0;
    }

    @Override
    public boolean claimBootstrap(TenantContext tenant, InitialAdminBootstrap bootstrap) {
        int affected = jdbcTemplate.update(
                """
                INSERT INTO administration.initial_admin_bootstrap (
                    id, tenant_id, actor_identity_id, actor_binding_id,
                    administrative_role_id, administrative_grant_id,
                    completed_at, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id) DO NOTHING
                """,
                bootstrap.id(),
                tenant.tenantId(),
                bootstrap.actorIdentityId(),
                bootstrap.actorBindingId(),
                bootstrap.administrativeRoleId(),
                bootstrap.administrativeGrantId(),
                Timestamp.from(bootstrap.completedAt()),
                bootstrap.correlationId());
        return affected == 1;
    }

    @Override
    public UUID ensurePermission(
            TenantContext tenant,
            UUID proposedPermissionId,
            AdministrativePermission permission,
            Instant now) {
        jdbcTemplate.update(
                """
                INSERT INTO administration.administrative_permission (
                    id, tenant_id, resource_type, action, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, resource_type, action) DO NOTHING
                """,
                proposedPermissionId,
                tenant.tenantId(),
                permission.resourceType(),
                permission.action(),
                Timestamp.from(now));
        return jdbcTemplate.queryForObject(
                """
                SELECT id FROM administration.administrative_permission
                WHERE tenant_id = ? AND resource_type = ? AND action = ?
                """,
                UUID.class,
                tenant.tenantId(),
                permission.resourceType(),
                permission.action());
    }

    @Override
    public void insertRole(TenantContext tenant, AdministrativeRole role) {
        jdbcTemplate.update(
                """
                INSERT INTO administration.administrative_role (
                    id, tenant_id, code, name, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                role.id(),
                tenant.tenantId(),
                role.code(),
                role.name(),
                role.revision(),
                Timestamp.from(role.createdAt()),
                Timestamp.from(role.updatedAt()));
    }

    @Override
    public void attachPermissions(
            TenantContext tenant,
            UUID roleId,
            Collection<UUID> permissionIds,
            Instant now) {
        for (UUID permissionId : permissionIds) {
            jdbcTemplate.update(
                    """
                    INSERT INTO administration.administrative_role_permission (
                        tenant_id, role_id, permission_id, created_at)
                    VALUES (?, ?, ?, ?)
                    """,
                    tenant.tenantId(),
                    roleId,
                    permissionId,
                    Timestamp.from(now));
        }
    }

    @Override
    public void insertGrant(TenantContext tenant, AdministrativeGrant grant) {
        jdbcTemplate.update(
                """
                INSERT INTO administration.administrative_grant (
                    id, tenant_id, actor_identity_id, role_id,
                    scope_type, scope_resource_type, scope_ref_id,
                    state, valid_from, valid_until,
                    revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                grant.id(),
                tenant.tenantId(),
                grant.actorIdentityId(),
                grant.roleId(),
                grant.scope().type().name(),
                grant.scope().resourceType(),
                grant.scope().resourceId(),
                grant.state().name(),
                grant.validFrom() == null ? null : Timestamp.from(grant.validFrom()),
                grant.validUntil() == null ? null : Timestamp.from(grant.validUntil()),
                grant.revision(),
                Timestamp.from(grant.createdAt()),
                Timestamp.from(grant.updatedAt()));
    }
}
