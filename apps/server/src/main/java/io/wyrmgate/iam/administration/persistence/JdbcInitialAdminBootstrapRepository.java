package io.wyrmgate.iam.administration.persistence;

import io.wyrmgate.iam.administration.application.InitialAdminBootstrapRepository;
import io.wyrmgate.iam.administration.domain.AdministrativeGrant;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativeRole;
import io.wyrmgate.iam.administration.domain.InitialAdminAuthenticationAnchorType;
import io.wyrmgate.iam.administration.domain.InitialAdminBootstrap;
import io.wyrmgate.iam.administration.domain.PlatformOwnerAuthority;
import io.wyrmgate.iam.administration.domain.TenantOwnerAuthority;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC mutation adapter for the burn-once first administrative bootstrap transaction. */
public final class JdbcInitialAdminBootstrapRepository implements InitialAdminBootstrapRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcInitialAdminBootstrapRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Override
    public Optional<InitialAdminBootstrap> findBootstrap(TenantContext tenant) {
        List<InitialAdminBootstrap> rows = jdbcTemplate.query(
                """
                SELECT id, actor_identity_id, authentication_anchor_type,
                       actor_binding_id, actor_principal_id,
                       administrative_role_id, administrative_grant_id,
                       completed_at, correlation_id
                FROM administration.initial_admin_bootstrap
                WHERE tenant_id = ?
                """,
                (rs, rowNum) -> new InitialAdminBootstrap(
                        rs.getObject("id", UUID.class),
                        rs.getObject("actor_identity_id", UUID.class),
                        InitialAdminAuthenticationAnchorType.valueOf(
                                rs.getString("authentication_anchor_type")),
                        rs.getObject("actor_binding_id", UUID.class),
                        rs.getObject("actor_principal_id", UUID.class),
                        rs.getObject("administrative_role_id", UUID.class),
                        rs.getObject("administrative_grant_id", UUID.class),
                        rs.getTimestamp("completed_at").toInstant(),
                        rs.getObject("correlation_id", UUID.class)),
                tenant.tenantId());
        return rows.stream().findFirst();
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
    public boolean isGrantEffective(
            TenantContext tenant,
            UUID grantId,
            UUID actorIdentityId,
            Instant at) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM administration.administrative_grant
                WHERE tenant_id = ?
                  AND id = ?
                  AND actor_identity_id = ?
                  AND state = 'ACTIVE'
                  AND (valid_from IS NULL OR valid_from <= ?)
                  AND (valid_until IS NULL OR valid_until > ?)
                """,
                Integer.class,
                tenant.tenantId(),
                grantId,
                actorIdentityId,
                Timestamp.from(at),
                Timestamp.from(at));
        return count != null && count == 1;
    }

    @Override
    public boolean isTenantOwnerAuthorityEffective(
            TenantContext tenant,
            UUID grantId,
            UUID actorIdentityId,
            Instant at) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM administration.tenant_owner_authority owner_authority
                JOIN administration.administrative_grant grant_authority
                  ON grant_authority.tenant_id = owner_authority.tenant_id
                 AND grant_authority.id = owner_authority.administrative_grant_id
                WHERE owner_authority.tenant_id = ?
                  AND owner_authority.administrative_grant_id = ?
                  AND owner_authority.actor_identity_id = ?
                  AND owner_authority.state = 'ACTIVE'
                  AND grant_authority.state = 'ACTIVE'
                  AND (grant_authority.valid_from IS NULL OR grant_authority.valid_from <= ?)
                  AND (grant_authority.valid_until IS NULL OR grant_authority.valid_until > ?)
                """,
                Integer.class,
                tenant.tenantId(),
                grantId,
                actorIdentityId,
                Timestamp.from(at),
                Timestamp.from(at));
        return count != null && count == 1;
    }

    @Override
    public boolean isPlatformOwnerAuthorityEffective(
            TenantContext actorTenant,
            UUID actorIdentityId,
            UUID actorPrincipalId,
            Instant at) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM administration.platform_owner_authority
                WHERE actor_tenant_id = ?
                  AND actor_identity_id = ?
                  AND actor_principal_id = ?
                  AND state = 'ACTIVE'
                  AND (valid_from IS NULL OR valid_from <= ?)
                  AND (valid_until IS NULL OR valid_until > ?)
                """,
                Integer.class,
                actorTenant.tenantId(),
                actorIdentityId,
                actorPrincipalId,
                Timestamp.from(at),
                Timestamp.from(at));
        return count != null && count == 1;
    }

    @Override
    public boolean claimBootstrap(TenantContext tenant, InitialAdminBootstrap bootstrap) {
        int affected = jdbcTemplate.update(
                """
                INSERT INTO administration.initial_admin_bootstrap (
                    id, tenant_id, actor_identity_id, authentication_anchor_type,
                    actor_binding_id, actor_principal_id,
                    administrative_role_id, administrative_grant_id,
                    completed_at, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id) DO NOTHING
                """,
                bootstrap.id(),
                tenant.tenantId(),
                bootstrap.actorIdentityId(),
                bootstrap.authenticationAnchorType().name(),
                bootstrap.actorBindingId(),
                bootstrap.actorPrincipalId(),
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
                    scope_type, scope_resource_type, scope_ref_id, scope_key,
                    state, valid_from, valid_until,
                    grantable, delegable, authority_basis_grant_id,
                    revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                grant.id(),
                tenant.tenantId(),
                grant.actorIdentityId(),
                grant.roleId(),
                grant.scope().type().name(),
                grant.scope().resourceType(),
                grant.scope().resourceId(),
                grant.scope().scopeKey(),
                grant.state().name(),
                grant.validFrom() == null ? null : Timestamp.from(grant.validFrom()),
                grant.validUntil() == null ? null : Timestamp.from(grant.validUntil()),
                grant.grantable(),
                grant.delegable(),
                grant.authorityBasisGrantId(),
                grant.revision(),
                Timestamp.from(grant.createdAt()),
                Timestamp.from(grant.updatedAt()));
    }

    @Override
    public void insertTenantOwnerAuthority(TenantContext tenant, TenantOwnerAuthority authority) {
        jdbcTemplate.update(
                """
                INSERT INTO administration.tenant_owner_authority (
                    id, tenant_id, actor_identity_id, administrative_grant_id,
                    state, created_at, revoked_at, revision, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                authority.id(),
                tenant.tenantId(),
                authority.actorIdentityId(),
                authority.administrativeGrantId(),
                authority.state().name(),
                Timestamp.from(authority.createdAt()),
                authority.revokedAt() == null ? null : Timestamp.from(authority.revokedAt()),
                authority.revision(),
                Timestamp.from(authority.updatedAt()));
    }

    @Override
    public void insertPlatformOwnerAuthority(PlatformOwnerAuthority authority) {
        jdbcTemplate.update(
                """
                INSERT INTO administration.platform_owner_authority (
                    id, actor_tenant_id, actor_identity_id, actor_principal_id,
                    state, grantable, delegable, authority_basis_id,
                    valid_from, valid_until, revoked_by_identity_id, revoked_at,
                    correlation_id, causation_id, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                authority.id(),
                authority.actorTenantId(),
                authority.actorIdentityId(),
                authority.actorPrincipalId(),
                authority.state().name(),
                authority.grantable(),
                authority.delegable(),
                authority.authorityBasisId(),
                authority.validFrom() == null ? null : Timestamp.from(authority.validFrom()),
                authority.validUntil() == null ? null : Timestamp.from(authority.validUntil()),
                authority.revokedByIdentityId(),
                authority.revokedAt() == null ? null : Timestamp.from(authority.revokedAt()),
                authority.correlationId(),
                authority.causationId(),
                authority.revision(),
                Timestamp.from(authority.createdAt()),
                Timestamp.from(authority.updatedAt()));
    }
}
