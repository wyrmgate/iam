package io.wyrmgate.iam.administration.persistence;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorityRepository;
import io.wyrmgate.iam.administration.domain.AdministrativeGrant;
import io.wyrmgate.iam.administration.domain.AdministrativeGrantState;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativeRole;
import io.wyrmgate.iam.administration.domain.AdministrativeScope;
import io.wyrmgate.iam.administration.domain.AdministrativeScopeType;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC adapter for Administration-owned direct role/grant management. */
public final class JdbcAdministrativeAuthorityRepository implements AdministrativeAuthorityRepository {

    private final JdbcTemplate jdbc;

    public JdbcAdministrativeAuthorityRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public AdministrativeRole createRole(
            TenantContext tenant,
            UUID id,
            String code,
            String name,
            Set<AdministrativePermission> permissions,
            Instant now) {
        if (permissions == null || permissions.isEmpty()) {
            throw new IllegalArgumentException("permissions must not be empty");
        }
        jdbc.update(
                """
                INSERT INTO administration.administrative_role (
                    id, tenant_id, code, name, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, ?, ?)
                """,
                id,
                tenant.tenantId(),
                code,
                name,
                Timestamp.from(now),
                Timestamp.from(now));
        for (AdministrativePermission permission : permissions) {
            UUID permissionId = ensurePermission(tenant, permission, now);
            jdbc.update(
                    """
                    INSERT INTO administration.administrative_role_permission (
                        tenant_id, role_id, permission_id, created_at)
                    VALUES (?, ?, ?, ?)
                    """,
                    tenant.tenantId(), id, permissionId, Timestamp.from(now));
        }
        return findRole(tenant, id).orElseThrow();
    }

    @Override
    public Optional<AdministrativeRole> findRole(TenantContext tenant, UUID roleId) {
        List<RoleRow> rows = jdbc.query(
                """
                SELECT id, code, name, revision, created_at, updated_at
                FROM administration.administrative_role
                WHERE tenant_id = ? AND id = ?
                """,
                (rs, rowNum) -> new RoleRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getLong("revision"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                tenant.tenantId(),
                roleId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        RoleRow row = rows.getFirst();
        return Optional.of(toRole(tenant, row));
    }

    @Override
    public AdministrativeRole renameRole(
            TenantContext tenant,
            UUID roleId,
            String name,
            long expectedRevision,
            Instant now) {
        int affected = jdbc.update(
                """
                UPDATE administration.administrative_role
                SET name = ?, revision = revision + 1, updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ?
                """,
                name,
                Timestamp.from(now),
                tenant.tenantId(),
                roleId,
                expectedRevision);
        requireUpdated(affected, "administrative-role", roleId, expectedRevision);
        return findRole(tenant, roleId).orElseThrow();
    }

    @Override
    public AdministrativeRole addRolePermission(
            TenantContext tenant,
            UUID roleId,
            AdministrativePermission permission,
            long expectedRevision,
            Instant now) {
        UUID permissionId = ensurePermission(tenant, permission, now);
        jdbc.update(
                """
                INSERT INTO administration.administrative_role_permission (
                    tenant_id, role_id, permission_id, created_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (tenant_id, role_id, permission_id) DO NOTHING
                """,
                tenant.tenantId(), roleId, permissionId, Timestamp.from(now));
        int affected = jdbc.update(
                """
                UPDATE administration.administrative_role
                SET revision = revision + 1, updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ?
                """,
                Timestamp.from(now), tenant.tenantId(), roleId, expectedRevision);
        requireUpdated(affected, "administrative-role", roleId, expectedRevision);
        return findRole(tenant, roleId).orElseThrow();
    }

    @Override
    public AdministrativeRole removeRolePermission(
            TenantContext tenant,
            UUID roleId,
            AdministrativePermission permission,
            long expectedRevision,
            Instant now) {
        jdbc.update(
                """
                DELETE FROM administration.administrative_role_permission rp
                USING administration.administrative_permission p
                WHERE rp.tenant_id = ?
                  AND rp.role_id = ?
                  AND rp.permission_id = p.id
                  AND p.tenant_id = rp.tenant_id
                  AND p.resource_type = ?
                  AND p.action = ?
                """,
                tenant.tenantId(), roleId, permission.resourceType(), permission.action());
        int affected = jdbc.update(
                """
                UPDATE administration.administrative_role
                SET revision = revision + 1, updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ?
                """,
                Timestamp.from(now), tenant.tenantId(), roleId, expectedRevision);
        requireUpdated(affected, "administrative-role", roleId, expectedRevision);
        return findRole(tenant, roleId).orElseThrow();
    }

    @Override
    public List<AdministrativeRole> listRoles(
            TenantContext tenant,
            Instant afterCreatedAt,
            UUID afterId,
            int limit) {
        List<RoleRow> rows;
        if (afterCreatedAt == null && afterId == null) {
            rows = jdbc.query(
                    """
                    SELECT id, code, name, revision, created_at, updated_at
                    FROM administration.administrative_role
                    WHERE tenant_id = ?
                    ORDER BY created_at, id
                    LIMIT ?
                    """,
                    (rs, rowNum) -> roleRow(rs),
                    tenant.tenantId(),
                    limit);
        } else {
            requireCursor(afterCreatedAt, afterId);
            rows = jdbc.query(
                    """
                    SELECT id, code, name, revision, created_at, updated_at
                    FROM administration.administrative_role
                    WHERE tenant_id = ?
                      AND (created_at, id) > (?, ?)
                    ORDER BY created_at, id
                    LIMIT ?
                    """,
                    (rs, rowNum) -> roleRow(rs),
                    tenant.tenantId(),
                    Timestamp.from(afterCreatedAt),
                    afterId,
                    limit);
        }
        return rows.stream().map(row -> toRole(tenant, row)).toList();
    }

    @Override
    public Optional<AdministrativeGrant> findGrant(TenantContext tenant, UUID grantId) {
        return jdbc.query(
                        grantSelect() + " WHERE g.tenant_id = ? AND g.id = ?",
                        (rs, rowNum) -> grantRow(rs),
                        tenant.tenantId(),
                        grantId)
                .stream()
                .findFirst();
    }

    @Override
    public AdministrativeGrant createGrant(
            TenantContext tenant,
            UUID id,
            UUID actorIdentityId,
            UUID roleId,
            AdministrativeScope scope,
            Instant validFrom,
            Instant validUntil,
            boolean grantable,
            boolean delegable,
            UUID authorityBasisGrantId,
            Instant now) {
        jdbc.update(
                """
                INSERT INTO administration.administrative_grant (
                    id, tenant_id, actor_identity_id, role_id,
                    scope_type, scope_resource_type, scope_ref_id, scope_key,
                    state, valid_from, valid_until,
                    grantable, delegable, authority_basis_grant_id,
                    revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, 1, ?, ?)
                """,
                id,
                tenant.tenantId(),
                actorIdentityId,
                roleId,
                scope.type().name(),
                scope.resourceType(),
                scope.resourceId(),
                scope.scopeKey(),
                nullableTimestamp(validFrom),
                nullableTimestamp(validUntil),
                grantable,
                delegable,
                authorityBasisGrantId,
                Timestamp.from(now),
                Timestamp.from(now));
        return findGrant(tenant, id).orElseThrow();
    }

    @Override
    public AdministrativeGrant revokeGrant(
            TenantContext tenant,
            UUID grantId,
            long expectedRevision,
            Instant now) {
        int affected = jdbc.update(
                """
                UPDATE administration.administrative_grant
                SET state = 'REVOKED', revision = revision + 1, updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ? AND state = 'ACTIVE'
                """,
                Timestamp.from(now), tenant.tenantId(), grantId, expectedRevision);
        requireUpdated(affected, "administrative-grant", grantId, expectedRevision);
        return findGrant(tenant, grantId).orElseThrow();
    }

    @Override
    public List<AdministrativeGrant> listGrants(
            TenantContext tenant,
            Instant afterCreatedAt,
            UUID afterId,
            int limit) {
        if (afterCreatedAt == null && afterId == null) {
            return jdbc.query(
                    grantSelect()
                            + """
                             WHERE g.tenant_id = ?
                             ORDER BY g.created_at, g.id
                             LIMIT ?
                             """,
                    (rs, rowNum) -> grantRow(rs),
                    tenant.tenantId(),
                    limit);
        }
        requireCursor(afterCreatedAt, afterId);
        return jdbc.query(
                grantSelect()
                        + """
                         WHERE g.tenant_id = ?
                           AND (g.created_at, g.id) > (?, ?)
                         ORDER BY g.created_at, g.id
                         LIMIT ?
                         """,
                (rs, rowNum) -> grantRow(rs),
                tenant.tenantId(),
                Timestamp.from(afterCreatedAt),
                afterId,
                limit);
    }

    @Override
    public List<AdministrativeGrant> findAuthorityBearingGrantsByRole(
            TenantContext tenant,
            UUID roleId,
            Instant now,
            int limit) {
        return jdbc.query(
                grantSelect()
                        + """
                         WHERE g.tenant_id = ?
                           AND g.role_id = ?
                           AND g.state = 'ACTIVE'
                           AND (g.valid_until IS NULL OR g.valid_until > ?)
                         ORDER BY g.created_at, g.id
                         LIMIT ?
                         """,
                (rs, rowNum) -> grantRow(rs),
                tenant.tenantId(),
                roleId,
                Timestamp.from(now),
                limit);
    }

    @Override
    public List<AdministrativeGrant> findActorGrants(
            TenantContext tenant,
            UUID actorIdentityId,
            int limit) {
        return jdbc.query(
                grantSelect()
                        + """
                         WHERE g.tenant_id = ?
                           AND g.actor_identity_id = ?
                         ORDER BY g.created_at, g.id
                         LIMIT ?
                         """,
                (rs, rowNum) -> grantRow(rs),
                tenant.tenantId(),
                actorIdentityId,
                limit);
    }

    private UUID ensurePermission(
            TenantContext tenant, AdministrativePermission permission, Instant now) {
        UUID proposedId = UUID.randomUUID();
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
                SELECT id
                FROM administration.administrative_permission
                WHERE tenant_id = ? AND resource_type = ? AND action = ?
                """,
                UUID.class,
                tenant.tenantId(),
                permission.resourceType(),
                permission.action());
    }

    private AdministrativeRole toRole(TenantContext tenant, RoleRow row) {
        Set<AdministrativePermission> permissions = new LinkedHashSet<>(jdbc.query(
                """
                SELECT p.resource_type, p.action
                FROM administration.administrative_role_permission rp
                JOIN administration.administrative_permission p
                  ON p.tenant_id = rp.tenant_id AND p.id = rp.permission_id
                WHERE rp.tenant_id = ? AND rp.role_id = ?
                ORDER BY p.resource_type, p.action
                """,
                (rs, rowNum) -> new AdministrativePermission(
                        rs.getString("resource_type"), rs.getString("action")),
                tenant.tenantId(),
                row.id()));
        return new AdministrativeRole(
                row.id(),
                row.code(),
                row.name(),
                permissions,
                row.revision(),
                row.createdAt(),
                row.updatedAt());
    }

    private static RoleRow roleRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RoleRow(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static String grantSelect() {
        return """
                SELECT g.id, g.actor_identity_id, g.role_id,
                       g.scope_type, g.scope_resource_type, g.scope_ref_id, g.scope_key,
                       g.state, g.valid_from, g.valid_until,
                       g.grantable, g.delegable, g.authority_basis_grant_id,
                       g.revision, g.created_at, g.updated_at
                FROM administration.administrative_grant g
                """;
    }

    private static AdministrativeGrant grantRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AdministrativeGrant(
                rs.getObject("id", UUID.class),
                rs.getObject("actor_identity_id", UUID.class),
                rs.getObject("role_id", UUID.class),
                new AdministrativeScope(
                        AdministrativeScopeType.valueOf(rs.getString("scope_type")),
                        rs.getString("scope_resource_type"),
                        rs.getObject("scope_ref_id", UUID.class),
                        rs.getString("scope_key")),
                AdministrativeGrantState.valueOf(rs.getString("state")),
                nullableInstant(rs.getTimestamp("valid_from")),
                nullableInstant(rs.getTimestamp("valid_until")),
                rs.getBoolean("grantable"),
                rs.getBoolean("delegable"),
                rs.getObject("authority_basis_grant_id", UUID.class),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static Timestamp nullableTimestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant nullableInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static void requireUpdated(
            int affected, String resourceType, UUID id, long expectedRevision) {
        if (affected != 1) {
            throw new StaleWriteException(resourceType, id, expectedRevision);
        }
    }

    private static void requireCursor(Instant afterCreatedAt, UUID afterId) {
        if (afterCreatedAt == null || afterId == null) {
            throw new IllegalArgumentException("afterCreatedAt and afterId must both be supplied");
        }
    }

    private record RoleRow(
            UUID id,
            String code,
            String name,
            long revision,
            Instant createdAt,
            Instant updatedAt) {
    }
}
