package io.wyrmgate.iam.administration.persistence;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorityProjectionRepository;
import io.wyrmgate.iam.administration.application.AdministrativeDelegatedAuthorityCandidate;
import io.wyrmgate.iam.administration.domain.AdministrativeBreakGlassOperation;
import io.wyrmgate.iam.administration.domain.AdministrativeBreakGlassState;
import io.wyrmgate.iam.administration.domain.AdministrativeDelegation;
import io.wyrmgate.iam.administration.domain.AdministrativeDelegationState;
import io.wyrmgate.iam.administration.domain.AdministrativeElevation;
import io.wyrmgate.iam.administration.domain.AdministrativeElevationState;
import io.wyrmgate.iam.administration.domain.AdministrativeGrant;
import io.wyrmgate.iam.administration.domain.AdministrativeGrantState;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativeScope;
import io.wyrmgate.iam.administration.domain.AdministrativeScopeType;
import io.wyrmgate.iam.administration.domain.AuthenticationAssuranceLevel;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC adapter for the Administration-owned current-actor authority read projection. */
public final class JdbcAdministrativeAuthorityProjectionRepository
        implements AdministrativeAuthorityProjectionRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcAdministrativeAuthorityProjectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Override
    public List<GrantCandidate> findGrantCandidates(
            TenantContext tenant, UUID actorIdentityId, int limit) {
        return jdbcTemplate.query(
                """
                SELECT p.resource_type AS p_resource_type, p.action AS p_action,
                       g.id, g.actor_identity_id, g.role_id,
                       g.scope_type, g.scope_resource_type, g.scope_ref_id, g.scope_key,
                       g.state, g.valid_from, g.valid_until,
                       g.grantable, g.delegable, g.authority_basis_grant_id,
                       g.revision, g.created_at, g.updated_at
                FROM administration.administrative_grant g
                JOIN administration.administrative_role_permission rp
                  ON rp.tenant_id = g.tenant_id AND rp.role_id = g.role_id
                JOIN administration.administrative_permission p
                  ON p.tenant_id = rp.tenant_id AND p.id = rp.permission_id
                WHERE g.tenant_id = ? AND g.actor_identity_id = ?
                ORDER BY p.resource_type, p.action, g.created_at, g.id
                LIMIT ?
                """,
                (rs, rowNum) -> new GrantCandidate(
                        permission(rs.getString("p_resource_type"), rs.getString("p_action")),
                        new AdministrativeGrant(
                                rs.getObject("id", UUID.class),
                                rs.getObject("actor_identity_id", UUID.class),
                                rs.getObject("role_id", UUID.class),
                                scope(rs.getString("scope_type"), rs.getString("scope_resource_type"),
                                        rs.getObject("scope_ref_id", UUID.class), rs.getString("scope_key")),
                                AdministrativeGrantState.valueOf(rs.getString("state")),
                                nullableInstant(rs.getTimestamp("valid_from")),
                                nullableInstant(rs.getTimestamp("valid_until")),
                                rs.getBoolean("grantable"), rs.getBoolean("delegable"),
                                rs.getObject("authority_basis_grant_id", UUID.class),
                                rs.getLong("revision"), rs.getTimestamp("created_at").toInstant(),
                                rs.getTimestamp("updated_at").toInstant())),
                tenant.tenantId(), actorIdentityId, limit);
    }

    @Override
    public List<DelegationCandidate> findDelegationCandidates(
            TenantContext tenant, UUID actorIdentityId, int limit) {
        return jdbcTemplate.query(
                """
                SELECT p.resource_type AS p_resource_type, p.action AS p_action,
                       d.id AS d_id, d.delegate_identity_id, d.delegator_identity_id,
                       d.source_grant_id, d.role_id AS d_role_id,
                       d.scope_type AS d_scope_type, d.scope_resource_type AS d_scope_resource_type,
                       d.scope_ref_id AS d_scope_ref_id, d.scope_key AS d_scope_key,
                       d.state AS d_state, d.valid_from AS d_valid_from, d.valid_until AS d_valid_until,
                       d.created_by_identity_id, d.revoked_by_identity_id, d.revoked_at,
                       d.correlation_id, d.causation_id, d.revision AS d_revision,
                       d.created_at AS d_created_at, d.updated_at AS d_updated_at,
                       g.id AS g_id, g.actor_identity_id, g.role_id AS g_role_id,
                       g.scope_type AS g_scope_type, g.scope_resource_type AS g_scope_resource_type,
                       g.scope_ref_id AS g_scope_ref_id, g.scope_key AS g_scope_key,
                       g.state AS g_state, g.valid_from AS g_valid_from, g.valid_until AS g_valid_until,
                       g.grantable, g.delegable, g.authority_basis_grant_id,
                       g.revision AS g_revision, g.created_at AS g_created_at, g.updated_at AS g_updated_at
                FROM administration.administrative_delegation d
                JOIN administration.administrative_grant g
                  ON g.tenant_id = d.tenant_id AND g.id = d.source_grant_id
                JOIN administration.administrative_role_permission rp
                  ON rp.tenant_id = d.tenant_id AND rp.role_id = d.role_id
                JOIN administration.administrative_permission p
                  ON p.tenant_id = rp.tenant_id AND p.id = rp.permission_id
                WHERE d.tenant_id = ? AND d.delegate_identity_id = ?
                ORDER BY p.resource_type, p.action, d.created_at, d.id
                LIMIT ?
                """,
                (rs, rowNum) -> new DelegationCandidate(
                        permission(rs.getString("p_resource_type"), rs.getString("p_action")),
                        new AdministrativeDelegatedAuthorityCandidate(
                                new AdministrativeDelegation(
                                        rs.getObject("d_id", UUID.class),
                                        rs.getObject("delegate_identity_id", UUID.class),
                                        rs.getObject("delegator_identity_id", UUID.class),
                                        rs.getObject("source_grant_id", UUID.class),
                                        rs.getObject("d_role_id", UUID.class),
                                        scope(rs.getString("d_scope_type"), rs.getString("d_scope_resource_type"),
                                                rs.getObject("d_scope_ref_id", UUID.class), rs.getString("d_scope_key")),
                                        AdministrativeDelegationState.valueOf(rs.getString("d_state")),
                                        nullableInstant(rs.getTimestamp("d_valid_from")),
                                        rs.getTimestamp("d_valid_until").toInstant(),
                                        rs.getObject("created_by_identity_id", UUID.class),
                                        rs.getObject("revoked_by_identity_id", UUID.class),
                                        nullableInstant(rs.getTimestamp("revoked_at")),
                                        rs.getObject("correlation_id", UUID.class),
                                        rs.getObject("causation_id", UUID.class),
                                        rs.getLong("d_revision"),
                                        rs.getTimestamp("d_created_at").toInstant(),
                                        rs.getTimestamp("d_updated_at").toInstant()),
                                new AdministrativeGrant(
                                        rs.getObject("g_id", UUID.class),
                                        rs.getObject("actor_identity_id", UUID.class),
                                        rs.getObject("g_role_id", UUID.class),
                                        scope(rs.getString("g_scope_type"), rs.getString("g_scope_resource_type"),
                                                rs.getObject("g_scope_ref_id", UUID.class), rs.getString("g_scope_key")),
                                        AdministrativeGrantState.valueOf(rs.getString("g_state")),
                                        nullableInstant(rs.getTimestamp("g_valid_from")),
                                        nullableInstant(rs.getTimestamp("g_valid_until")),
                                        rs.getBoolean("grantable"), rs.getBoolean("delegable"),
                                        rs.getObject("authority_basis_grant_id", UUID.class),
                                        rs.getLong("g_revision"),
                                        rs.getTimestamp("g_created_at").toInstant(),
                                        rs.getTimestamp("g_updated_at").toInstant()))),
                tenant.tenantId(), actorIdentityId, limit);
    }

    @Override
    public List<ElevationCandidate> findElevationCandidates(
            TenantContext tenant, UUID actorIdentityId, int limit) {
        return jdbcTemplate.query(
                """
                SELECT p.resource_type AS p_resource_type, p.action AS p_action,
                       e.id, e.beneficiary_identity_id, e.initiator_identity_id,
                       e.authority_basis_grant_id, e.role_id,
                       e.scope_type, e.scope_resource_type, e.scope_ref_id, e.scope_key,
                       e.valid_from, e.valid_until, e.request_fingerprint, e.state,
                       e.approval_case_id, e.approval_plan_fingerprint,
                       e.activated_at, e.denied_at, e.cancelled_at, e.revoked_at,
                       e.correlation_id, e.causation_id, e.revision, e.created_at, e.updated_at
                FROM administration.administrative_elevation e
                JOIN administration.administrative_role_permission rp
                  ON rp.tenant_id = e.tenant_id AND rp.role_id = e.role_id
                JOIN administration.administrative_permission p
                  ON p.tenant_id = rp.tenant_id AND p.id = rp.permission_id
                WHERE e.tenant_id = ? AND e.beneficiary_identity_id = ?
                ORDER BY p.resource_type, p.action, e.created_at, e.id
                LIMIT ?
                """,
                (rs, rowNum) -> new ElevationCandidate(
                        permission(rs.getString("p_resource_type"), rs.getString("p_action")),
                        new AdministrativeElevation(
                                rs.getObject("id", UUID.class),
                                rs.getObject("beneficiary_identity_id", UUID.class),
                                rs.getObject("initiator_identity_id", UUID.class),
                                rs.getObject("authority_basis_grant_id", UUID.class),
                                rs.getObject("role_id", UUID.class),
                                scope(rs.getString("scope_type"), rs.getString("scope_resource_type"),
                                        rs.getObject("scope_ref_id", UUID.class), rs.getString("scope_key")),
                                nullableInstant(rs.getTimestamp("valid_from")),
                                rs.getTimestamp("valid_until").toInstant(),
                                rs.getString("request_fingerprint"),
                                AdministrativeElevationState.valueOf(rs.getString("state")),
                                rs.getObject("approval_case_id", UUID.class),
                                rs.getString("approval_plan_fingerprint"),
                                nullableInstant(rs.getTimestamp("activated_at")),
                                nullableInstant(rs.getTimestamp("denied_at")),
                                nullableInstant(rs.getTimestamp("cancelled_at")),
                                nullableInstant(rs.getTimestamp("revoked_at")),
                                rs.getObject("correlation_id", UUID.class),
                                rs.getObject("causation_id", UUID.class),
                                rs.getLong("revision"), rs.getTimestamp("created_at").toInstant(),
                                rs.getTimestamp("updated_at").toInstant())),
                tenant.tenantId(), actorIdentityId, limit);
    }

    @Override
    public List<BreakGlassCandidate> findBreakGlassCandidates(
            TenantContext tenant, UUID actorIdentityId, int limit) {
        return jdbcTemplate.query(
                """
                SELECT p.resource_type AS p_resource_type, p.action AS p_action,
                       b.id, b.actor_identity_id, b.role_id,
                       b.scope_type, b.scope_resource_type, b.scope_ref_id, b.scope_key,
                       b.reason, b.incident_reference, b.valid_from, b.valid_until,
                       b.activation_assurance_level, b.activation_authenticated_at,
                       b.activation_step_up_at, b.max_assurance_age_seconds,
                       b.state, b.activated_at, b.revoked_by_identity_id, b.revoked_at,
                       b.correlation_id, b.causation_id, b.revision, b.created_at, b.updated_at
                FROM administration.administrative_break_glass_operation b
                JOIN administration.administrative_role_permission rp
                  ON rp.tenant_id = b.tenant_id AND rp.role_id = b.role_id
                JOIN administration.administrative_permission p
                  ON p.tenant_id = rp.tenant_id AND p.id = rp.permission_id
                WHERE b.tenant_id = ? AND b.actor_identity_id = ?
                ORDER BY p.resource_type, p.action, b.created_at, b.id
                LIMIT ?
                """,
                (rs, rowNum) -> new BreakGlassCandidate(
                        permission(rs.getString("p_resource_type"), rs.getString("p_action")),
                        new AdministrativeBreakGlassOperation(
                                rs.getObject("id", UUID.class), rs.getObject("actor_identity_id", UUID.class),
                                rs.getObject("role_id", UUID.class),
                                scope(rs.getString("scope_type"), rs.getString("scope_resource_type"),
                                        rs.getObject("scope_ref_id", UUID.class), rs.getString("scope_key")),
                                rs.getString("reason"), rs.getString("incident_reference"),
                                rs.getTimestamp("valid_from").toInstant(),
                                rs.getTimestamp("valid_until").toInstant(),
                                AuthenticationAssuranceLevel.valueOf(rs.getString("activation_assurance_level")),
                                nullableInstant(rs.getTimestamp("activation_authenticated_at")),
                                rs.getTimestamp("activation_step_up_at").toInstant(),
                                rs.getLong("max_assurance_age_seconds"),
                                AdministrativeBreakGlassState.valueOf(rs.getString("state")),
                                rs.getTimestamp("activated_at").toInstant(),
                                rs.getObject("revoked_by_identity_id", UUID.class),
                                nullableInstant(rs.getTimestamp("revoked_at")),
                                rs.getObject("correlation_id", UUID.class), rs.getObject("causation_id", UUID.class),
                                rs.getLong("revision"), rs.getTimestamp("created_at").toInstant(),
                                rs.getTimestamp("updated_at").toInstant())),
                tenant.tenantId(), actorIdentityId, limit);
    }

    private static AdministrativePermission permission(String resourceType, String action) {
        return new AdministrativePermission(resourceType, action);
    }

    private static AdministrativeScope scope(
            String type, String resourceType, UUID resourceId, String scopeKey) {
        return new AdministrativeScope(
                AdministrativeScopeType.valueOf(type), resourceType, resourceId, scopeKey);
    }

    private static java.time.Instant nullableInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
