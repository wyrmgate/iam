package io.wyrmgate.iam.administration.persistence;

import io.wyrmgate.iam.administration.application.StandaloneTenantOwnerRecoveryRepository;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC adapter for the single ADR-0047 role-membership expansion. */
public final class JdbcStandaloneTenantOwnerRecoveryRepository
        implements StandaloneTenantOwnerRecoveryRepository {

    private final JdbcTemplate jdbc;
    private final IdGenerator ids;

    public JdbcStandaloneTenantOwnerRecoveryRepository(JdbcTemplate jdbc, IdGenerator ids) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    public long countGrantsForActor(TenantContext tenant, UUID actorIdentityId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM administration.administrative_grant WHERE tenant_id = ? AND actor_identity_id = ?",
                Long.class,
                tenant.tenantId(),
                actorIdentityId);
        return count == null ? 0 : count;
    }

    @Override
    public long countGrantsForRole(TenantContext tenant, UUID roleId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM administration.administrative_grant WHERE tenant_id = ? AND role_id = ?",
                Long.class,
                tenant.tenantId(),
                roleId);
        return count == null ? 0 : count;
    }

    @Override
    public long countDelegationsForRole(TenantContext tenant, UUID roleId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM administration.administrative_delegation WHERE tenant_id = ? AND role_id = ?",
                Long.class,
                tenant.tenantId(),
                roleId);
        return count == null ? 0 : count;
    }

    @Override
    public long countElevationsForRole(TenantContext tenant, UUID roleId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM administration.administrative_elevation WHERE tenant_id = ? AND role_id = ?",
                Long.class,
                tenant.tenantId(),
                roleId);
        return count == null ? 0 : count;
    }

    @Override
    public long countBreakGlassOperationsForRole(TenantContext tenant, UUID roleId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM administration.administrative_break_glass_operation WHERE tenant_id = ? AND role_id = ?",
                Long.class,
                tenant.tenantId(),
                roleId);
        return count == null ? 0 : count;
    }

    @Override
    public void upgradeLegacyRolePermissions(
            TenantContext tenant,
            UUID roleId,
            long expectedRevision,
            Set<AdministrativePermission> expectedLegacyPermissions,
            Set<AdministrativePermission> targetPermissions,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(roleId, "roleId");
        Objects.requireNonNull(expectedLegacyPermissions, "expectedLegacyPermissions");
        Objects.requireNonNull(targetPermissions, "targetPermissions");
        Objects.requireNonNull(now, "now");
        if (!targetPermissions.containsAll(expectedLegacyPermissions)) {
            throw new IllegalArgumentException("targetPermissions must contain the legacy profile");
        }
        if (!currentPermissionsMatch(tenant, roleId, expectedLegacyPermissions)) {
            throw new StaleWriteException("administrative-role", roleId, expectedRevision);
        }

        for (AdministrativePermission permission : targetPermissions) {
            ensurePermission(tenant, permission, now);
        }

        int affected = jdbc.update(
                """
                UPDATE administration.administrative_role
                SET revision = revision + 1, updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ?
                """,
                Timestamp.from(now),
                tenant.tenantId(),
                roleId,
                expectedRevision);
        if (affected != 1) {
            throw new StaleWriteException("administrative-role", roleId, expectedRevision);
        }

        jdbc.update(
                """
                INSERT INTO administration.administrative_role_permission (
                    tenant_id, role_id, permission_id, created_at)
                SELECT ?, ?, p.id, ?
                FROM administration.administrative_permission p
                WHERE p.tenant_id = ?
                  AND EXISTS (
                      SELECT 1
                      FROM (VALUES %s) AS requested(resource_type, action)
                      WHERE requested.resource_type = p.resource_type
                        AND requested.action = p.action
                  )
                ON CONFLICT (tenant_id, role_id, permission_id) DO NOTHING
                """.formatted(valuesSql(targetPermissions)),
                tenant.tenantId(),
                roleId,
                Timestamp.from(now),
                tenant.tenantId());

        if (!currentPermissionsMatch(tenant, roleId, targetPermissions)) {
            throw new IllegalStateException("Recovered role did not converge to the exact owner profile");
        }
    }

    private UUID ensurePermission(TenantContext tenant, AdministrativePermission permission, Instant now) {
        UUID proposedId = ids.nextId();
        jdbc.update(
                """
                INSERT INTO administration.administrative_permission (
                    id, tenant_id, resource_type, action, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, resource_type, action) DO NOTHING
                """,
                proposedId,
                tenant.tenantId(),
                permission.resourceType(),
                permission.action(),
                Timestamp.from(now));
        return jdbc.queryForObject(
                """
                SELECT id FROM administration.administrative_permission
                WHERE tenant_id = ? AND resource_type = ? AND action = ?
                """,
                UUID.class,
                tenant.tenantId(),
                permission.resourceType(),
                permission.action());
    }

    private boolean currentPermissionsMatch(
            TenantContext tenant,
            UUID roleId,
            Set<AdministrativePermission> expected) {
        Long count = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM administration.administrative_role_permission rp
                JOIN administration.administrative_permission p
                  ON p.tenant_id = rp.tenant_id AND p.id = rp.permission_id
                WHERE rp.tenant_id = ? AND rp.role_id = ?
                """,
                Long.class,
                tenant.tenantId(),
                roleId);
        if (count == null || count != expected.size()) {
            return false;
        }
        for (AdministrativePermission permission : expected) {
            Integer present = jdbc.queryForObject(
                    """
                    SELECT count(*)
                    FROM administration.administrative_role_permission rp
                    JOIN administration.administrative_permission p
                      ON p.tenant_id = rp.tenant_id AND p.id = rp.permission_id
                    WHERE rp.tenant_id = ? AND rp.role_id = ?
                      AND p.resource_type = ? AND p.action = ?
                    """,
                    Integer.class,
                    tenant.tenantId(),
                    roleId,
                    permission.resourceType(),
                    permission.action());
            if (present == null || present != 1) {
                return false;
            }
        }
        return true;
    }

    private static String valuesSql(Set<AdministrativePermission> permissions) {
        return permissions.stream()
                .map(permission -> "('"
                        + sqlLiteral(permission.resourceType())
                        + "','"
                        + sqlLiteral(permission.action())
                        + "')")
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElseThrow();
    }

    private static String sqlLiteral(String value) {
        return value.replace("'", "''");
    }
}
