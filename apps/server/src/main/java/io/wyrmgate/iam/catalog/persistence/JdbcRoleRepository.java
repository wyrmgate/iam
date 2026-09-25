package io.wyrmgate.iam.catalog.persistence;

import io.wyrmgate.iam.catalog.application.RoleRepository;
import io.wyrmgate.iam.catalog.domain.CatalogLifecycleState;
import io.wyrmgate.iam.catalog.domain.Role;
import io.wyrmgate.iam.catalog.domain.RoleVersion;
import io.wyrmgate.iam.catalog.domain.RoleVersionMember;
import io.wyrmgate.iam.platform.persistence.OptimisticUpdate;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcRoleRepository implements RoleRepository {

    private final JdbcTemplate jdbc;

    public JdbcRoleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertRole(TenantContext tenant, Role role) {
        jdbc.update("""
                INSERT INTO catalog.role (
                    id, tenant_id, role_type, application_id,
                    code, name, lifecycle_state, revision,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                role.id(), tenant.tenantId(), role.type().name(),
                role.applicationId(), role.code(), role.name(),
                role.lifecycleState().name(), role.revision(),
                Timestamp.from(role.createdAt()), Timestamp.from(role.updatedAt()));
    }

    @Override
    public Optional<Role> findRole(TenantContext tenant, UUID roleId) {
        return jdbc.query("""
                SELECT id, role_type, application_id, code, name,
                       lifecycle_state, revision, created_at, updated_at
                FROM catalog.role
                WHERE tenant_id = ? AND id = ?
                """, (rs,row) -> role(rs), tenant.tenantId(), roleId)
                .stream().findFirst();
    }

    @Override
    public Role retireRole(
            TenantContext tenant,
            UUID roleId,
            long expectedRevision,
            Instant now) {
        int affected = jdbc.update("""
                UPDATE catalog.role
                SET lifecycle_state = 'RETIRED',
                    revision = revision + 1,
                    updated_at = ?
                WHERE tenant_id = ? AND id = ?
                  AND revision = ? AND lifecycle_state = 'ACTIVE'
                """,
                Timestamp.from(now), tenant.tenantId(), roleId, expectedRevision);
        OptimisticUpdate.requireSingleRow(
                affected, "catalog-role", roleId, expectedRevision);
        return findRole(tenant, roleId).orElseThrow();
    }

    @Override
    public long nextVersionNumber(TenantContext tenant, UUID roleId) {
        Long current = jdbc.queryForObject("""
                SELECT max(version_number)
                FROM catalog.role_version
                WHERE tenant_id = ? AND role_id = ?
                """, Long.class, tenant.tenantId(), roleId);
        return current == null ? 1 : current + 1;
    }

    @Override
    public void insertVersion(
            TenantContext tenant,
            RoleVersion version,
            List<RoleVersionMember> members) {
        jdbc.update("""
                INSERT INTO catalog.role_version (
                    id, tenant_id, role_id, version_number,
                    state, content_hash, activated_at,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                version.id(), tenant.tenantId(), version.roleId(),
                version.versionNumber(), version.state().name(),
                version.contentHash(),
                version.activatedAt() == null
                        ? null : Timestamp.from(version.activatedAt()),
                Timestamp.from(version.createdAt()),
                Timestamp.from(version.updatedAt()));

        for (RoleVersionMember member : members) {
            jdbc.update("""
                    INSERT INTO catalog.role_version_member (
                        id, tenant_id, role_version_id, member_kind,
                        member_role_id, member_entitlement_id, ordinal)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    member.id(), tenant.tenantId(), version.id(),
                    member.kind().name(), member.memberRoleId(),
                    member.memberEntitlementId(), member.ordinal());
        }
    }

    @Override
    public Optional<RoleVersion> findVersion(
            TenantContext tenant, UUID roleVersionId) {
        return jdbc.query("""
                SELECT id, role_id, version_number, state, content_hash,
                       activated_at, created_at, updated_at
                FROM catalog.role_version
                WHERE tenant_id = ? AND id = ?
                """,
                (rs,row) -> version(rs),
                tenant.tenantId(), roleVersionId)
                .stream().findFirst();
    }

    @Override
    public Optional<RoleVersion> findActiveVersion(
            TenantContext tenant, UUID roleId) {
        return jdbc.query("""
                SELECT id, role_id, version_number, state, content_hash,
                       activated_at, created_at, updated_at
                FROM catalog.role_version
                WHERE tenant_id = ? AND role_id = ? AND state = 'ACTIVE'
                """,
                (rs,row) -> version(rs),
                tenant.tenantId(), roleId)
                .stream().findFirst();
    }

    @Override
    public List<RoleVersionMember> findMembers(
            TenantContext tenant, UUID roleVersionId) {
        return jdbc.query("""
                SELECT id, role_version_id, member_kind,
                       member_role_id, member_entitlement_id, ordinal
                FROM catalog.role_version_member
                WHERE tenant_id = ? AND role_version_id = ?
                ORDER BY ordinal, id
                """,
                (rs,row) -> new RoleVersionMember(
                        rs.getObject("id", UUID.class),
                        rs.getObject("role_version_id", UUID.class),
                        RoleVersionMember.MemberKind.valueOf(
                                rs.getString("member_kind")),
                        rs.getObject("member_role_id", UUID.class),
                        rs.getObject("member_entitlement_id", UUID.class),
                        rs.getInt("ordinal")),
                tenant.tenantId(), roleVersionId);
    }

    @Override
    public RoleVersion markReady(
            TenantContext tenant,
            UUID roleVersionId,
            Instant now) {
        int affected = jdbc.update("""
                UPDATE catalog.role_version
                SET state = 'READY', updated_at = ?
                WHERE tenant_id = ? AND id = ? AND state = 'DRAFT'
                """,
                Timestamp.from(now), tenant.tenantId(), roleVersionId);
        if (affected != 1) {
            throw new IllegalStateException(
                    "only DRAFT RoleVersion can become READY");
        }
        return findVersion(tenant, roleVersionId).orElseThrow();
    }

    @Override
    public RoleVersion activate(
            TenantContext tenant,
            UUID roleVersionId,
            Instant now) {
        RoleVersion selected = findVersion(tenant, roleVersionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "role version does not exist"));
        if (selected.state() != RoleVersion.State.READY) {
            throw new IllegalStateException(
                    "only READY RoleVersion can be activated");
        }

        jdbc.update("""
                UPDATE catalog.role_version
                SET state = 'SUPERSEDED', updated_at = ?
                WHERE tenant_id = ? AND role_id = ? AND state = 'ACTIVE'
                """,
                Timestamp.from(now), tenant.tenantId(), selected.roleId());

        int affected = jdbc.update("""
                UPDATE catalog.role_version
                SET state = 'ACTIVE', activated_at = ?, updated_at = ?
                WHERE tenant_id = ? AND id = ? AND state = 'READY'
                """,
                Timestamp.from(now), Timestamp.from(now),
                tenant.tenantId(), roleVersionId);
        if (affected != 1) {
            throw new IllegalStateException("RoleVersion activation failed");
        }
        return findVersion(tenant, roleVersionId).orElseThrow();
    }

    @Override
    public List<UUID> findActiveBusinessParents(
            TenantContext tenant,
            UUID applicationRoleId) {
        return jdbc.query("""
                SELECT DISTINCT r.id
                FROM catalog.role r
                JOIN catalog.role_version rv
                  ON rv.tenant_id = r.tenant_id
                 AND rv.role_id = r.id
                 AND rv.state = 'ACTIVE'
                JOIN catalog.role_version_member m
                  ON m.tenant_id = rv.tenant_id
                 AND m.role_version_id = rv.id
                 AND m.member_kind = 'APPLICATION_ROLE'
                 AND m.member_role_id = ?
                WHERE r.tenant_id = ?
                  AND r.role_type = 'BUSINESS'
                  AND r.lifecycle_state = 'ACTIVE'
                ORDER BY r.id
                """,
                (rs,row) -> rs.getObject(1, UUID.class),
                applicationRoleId, tenant.tenantId());
    }

    private static Role role(ResultSet rs) throws SQLException {
        return new Role(
                rs.getObject("id", UUID.class),
                Role.RoleType.valueOf(rs.getString("role_type")),
                rs.getObject("application_id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                CatalogLifecycleState.valueOf(
                        rs.getString("lifecycle_state")),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static RoleVersion version(ResultSet rs) throws SQLException {
        Timestamp activated = rs.getTimestamp("activated_at");
        return new RoleVersion(
                rs.getObject("id", UUID.class),
                rs.getObject("role_id", UUID.class),
                rs.getLong("version_number"),
                RoleVersion.State.valueOf(rs.getString("state")),
                rs.getString("content_hash"),
                activated == null ? null : activated.toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
