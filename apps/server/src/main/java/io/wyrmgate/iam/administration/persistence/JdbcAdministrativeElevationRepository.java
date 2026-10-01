package io.wyrmgate.iam.administration.persistence;

import io.wyrmgate.iam.administration.application.AdministrativeElevationRepository;
import io.wyrmgate.iam.administration.domain.AdministrativeElevation;
import io.wyrmgate.iam.administration.domain.AdministrativeElevationState;
import io.wyrmgate.iam.administration.domain.AdministrativeScope;
import io.wyrmgate.iam.administration.domain.AdministrativeScopeType;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcAdministrativeElevationRepository
        implements AdministrativeElevationRepository {

    private final JdbcTemplate jdbc;

    public JdbcAdministrativeElevationRepository(JdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<AdministrativeElevation> find(TenantContext tenant, UUID elevationId) {
        return jdbc.query(select() + " WHERE e.tenant_id = ? AND e.id = ?",
                        (rs, rowNum) -> row(rs), tenant.tenantId(), elevationId)
                .stream().findFirst();
    }

    @Override
    public AdministrativeElevation insert(
            TenantContext tenant, UUID id, UUID beneficiaryIdentityId,
            UUID initiatorIdentityId, UUID authorityBasisGrantId, UUID roleId,
            AdministrativeScope scope, Instant validFrom, Instant validUntil,
            String requestFingerprint, UUID correlationId, UUID causationId, Instant now) {
        jdbc.update("""
                INSERT INTO administration.administrative_elevation (
                    id, tenant_id, beneficiary_identity_id, initiator_identity_id,
                    authority_basis_grant_id, role_id,
                    scope_type, scope_resource_type, scope_ref_id, scope_key,
                    valid_from, valid_until, request_fingerprint, state,
                    correlation_id, causation_id, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?, ?, 1, ?, ?)
                """,
                id, tenant.tenantId(), beneficiaryIdentityId, initiatorIdentityId,
                authorityBasisGrantId, roleId,
                scope.type().name(), scope.resourceType(), scope.resourceId(), scope.scopeKey(),
                ts(validFrom), Timestamp.from(validUntil), requestFingerprint,
                correlationId, causationId, Timestamp.from(now), Timestamp.from(now));
        return find(tenant, id).orElseThrow();
    }

    @Override
    public AdministrativeElevation bindApproval(
            TenantContext tenant, UUID elevationId, long expectedRevision,
            UUID approvalCaseId, String approvalPlanFingerprint, Instant now) {
        int n = jdbc.update("""
                UPDATE administration.administrative_elevation
                SET approval_case_id = ?,
                    approval_plan_fingerprint = ?,
                    state = 'PENDING_APPROVAL',
                    revision = revision + 1,
                    updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ? AND state = 'REQUESTED'
                """,
                approvalCaseId, approvalPlanFingerprint, Timestamp.from(now),
                tenant.tenantId(), elevationId, expectedRevision);
        if (n != 1) throw new StaleWriteException("administrative-elevation", elevationId, expectedRevision);
        return find(tenant, elevationId).orElseThrow();
    }

    @Override
    public AdministrativeElevation transition(
            TenantContext tenant, UUID elevationId,
            AdministrativeElevationState expectedState,
            AdministrativeElevationState targetState,
            long expectedRevision, Instant now) {
        String timestampColumn = switch (targetState) {
            case ACTIVE -> "activated_at";
            case DENIED -> "denied_at";
            case CANCELLED -> "cancelled_at";
            case REVOKED -> "revoked_at";
            case REQUESTED, PENDING_APPROVAL -> null;
        };
        String extra = timestampColumn == null ? "" : ", " + timestampColumn + " = ?";
        String sql = "UPDATE administration.administrative_elevation SET state = ?, revision = revision + 1, updated_at = ?"
                + extra + " WHERE tenant_id = ? AND id = ? AND revision = ? AND state = ?";
        int n;
        if (timestampColumn == null) {
            n = jdbc.update(sql, targetState.name(), Timestamp.from(now),
                    tenant.tenantId(), elevationId, expectedRevision, expectedState.name());
        } else {
            n = jdbc.update(sql, targetState.name(), Timestamp.from(now), Timestamp.from(now),
                    tenant.tenantId(), elevationId, expectedRevision, expectedState.name());
        }
        if (n != 1) throw new StaleWriteException("administrative-elevation", elevationId, expectedRevision);
        return find(tenant, elevationId).orElseThrow();
    }

    @Override
    public List<AdministrativeElevation> list(
            TenantContext tenant, Instant afterCreatedAt, UUID afterId, int limit) {
        if (afterCreatedAt == null && afterId == null) {
            return jdbc.query(select() + " WHERE e.tenant_id = ? ORDER BY e.created_at, e.id LIMIT ?",
                    (rs, rowNum) -> row(rs), tenant.tenantId(), limit);
        }
        if (afterCreatedAt == null || afterId == null) {
            throw new IllegalArgumentException("both continuation values are required");
        }
        return jdbc.query(select()
                        + " WHERE e.tenant_id = ? AND (e.created_at, e.id) > (?, ?) ORDER BY e.created_at, e.id LIMIT ?",
                (rs, rowNum) -> row(rs), tenant.tenantId(), Timestamp.from(afterCreatedAt), afterId, limit);
    }

    private static String select() {
        return """
                SELECT e.id, e.beneficiary_identity_id, e.initiator_identity_id,
                       e.authority_basis_grant_id, e.role_id,
                       e.scope_type, e.scope_resource_type, e.scope_ref_id, e.scope_key,
                       e.valid_from, e.valid_until, e.request_fingerprint, e.state,
                       e.approval_case_id, e.approval_plan_fingerprint,
                       e.activated_at, e.denied_at, e.cancelled_at, e.revoked_at,
                       e.correlation_id, e.causation_id, e.revision, e.created_at, e.updated_at
                FROM administration.administrative_elevation e
                """;
    }

    private static AdministrativeElevation row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AdministrativeElevation(
                rs.getObject("id", UUID.class),
                rs.getObject("beneficiary_identity_id", UUID.class),
                rs.getObject("initiator_identity_id", UUID.class),
                rs.getObject("authority_basis_grant_id", UUID.class),
                rs.getObject("role_id", UUID.class),
                new AdministrativeScope(
                        AdministrativeScopeType.valueOf(rs.getString("scope_type")),
                        rs.getString("scope_resource_type"),
                        rs.getObject("scope_ref_id", UUID.class),
                        rs.getString("scope_key")),
                instant(rs.getTimestamp("valid_from")),
                rs.getTimestamp("valid_until").toInstant(),
                rs.getString("request_fingerprint"),
                AdministrativeElevationState.valueOf(rs.getString("state")),
                rs.getObject("approval_case_id", UUID.class),
                rs.getString("approval_plan_fingerprint"),
                instant(rs.getTimestamp("activated_at")),
                instant(rs.getTimestamp("denied_at")),
                instant(rs.getTimestamp("cancelled_at")),
                instant(rs.getTimestamp("revoked_at")),
                rs.getObject("correlation_id", UUID.class),
                rs.getObject("causation_id", UUID.class),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
