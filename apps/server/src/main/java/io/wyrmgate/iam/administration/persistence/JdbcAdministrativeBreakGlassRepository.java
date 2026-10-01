package io.wyrmgate.iam.administration.persistence;

import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassRepository;
import io.wyrmgate.iam.administration.domain.AdministrativeBreakGlassObligation;
import io.wyrmgate.iam.administration.domain.AdministrativeBreakGlassObligationState;
import io.wyrmgate.iam.administration.domain.AdministrativeBreakGlassObligationType;
import io.wyrmgate.iam.administration.domain.AdministrativeBreakGlassOperation;
import io.wyrmgate.iam.administration.domain.AdministrativeBreakGlassState;
import io.wyrmgate.iam.administration.domain.AdministrativeRole;
import io.wyrmgate.iam.administration.domain.AdministrativeScope;
import io.wyrmgate.iam.administration.domain.AdministrativeScopeType;
import io.wyrmgate.iam.administration.domain.AuthenticationAssuranceContext;
import io.wyrmgate.iam.administration.domain.AuthenticationAssuranceLevel;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC persistence for Administration-owned emergency authority and durable obligations. */
public final class JdbcAdministrativeBreakGlassRepository
        implements AdministrativeBreakGlassRepository {

    private final JdbcTemplate jdbc;

    public JdbcAdministrativeBreakGlassRepository(JdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<AdministrativeBreakGlassOperation> find(
            TenantContext tenant, UUID operationId) {
        return jdbc.query(
                        selectOperation()
                                + " WHERE b.tenant_id = ? AND b.id = ?",
                        (rs, rowNum) -> operationRow(rs),
                        tenant.tenantId(),
                        operationId)
                .stream()
                .findFirst();
    }

    @Override
    public AdministrativeBreakGlassOperation activate(
            TenantContext tenant,
            UUID operationId,
            UUID actorIdentityId,
            AdministrativeRole role,
            AdministrativeScope scope,
            String reason,
            String incidentReference,
            Instant validUntil,
            AuthenticationAssuranceContext assurance,
            Duration maxAssuranceAge,
            UUID notificationObligationId,
            UUID reviewObligationId,
            UUID correlationId,
            UUID causationId,
            Instant now) {
        jdbc.update(
                """
                INSERT INTO administration.administrative_break_glass_operation (
                    id, tenant_id, actor_identity_id, role_id,
                    scope_type, scope_resource_type, scope_ref_id, scope_key,
                    reason, incident_reference,
                    valid_from, valid_until,
                    activation_assurance_level,
                    activation_authenticated_at,
                    activation_step_up_at,
                    max_assurance_age_seconds,
                    state, activated_at,
                    correlation_id, causation_id,
                    revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 1, ?, ?)
                """,
                operationId,
                tenant.tenantId(),
                actorIdentityId,
                role.id(),
                scope.type().name(),
                scope.resourceType(),
                scope.resourceId(),
                scope.scopeKey(),
                reason,
                incidentReference,
                Timestamp.from(now),
                Timestamp.from(validUntil),
                assurance.level().name(),
                ts(assurance.authenticatedAt()),
                Timestamp.from(assurance.stepUpAt()),
                maxAssuranceAge.getSeconds(),
                Timestamp.from(now),
                correlationId,
                causationId,
                Timestamp.from(now),
                Timestamp.from(now));

        insertObligation(
                tenant,
                notificationObligationId,
                operationId,
                AdministrativeBreakGlassObligationType.SECURITY_NOTIFICATION,
                now);
        insertObligation(
                tenant,
                reviewObligationId,
                operationId,
                AdministrativeBreakGlassObligationType.POST_USE_REVIEW,
                now);

        return find(tenant, operationId).orElseThrow();
    }

    @Override
    public AdministrativeBreakGlassOperation revoke(
            TenantContext tenant,
            UUID operationId,
            UUID revokedByIdentityId,
            long expectedRevision,
            Instant now) {
        int updated = jdbc.update(
                """
                UPDATE administration.administrative_break_glass_operation
                SET state = 'REVOKED',
                    revoked_by_identity_id = ?,
                    revoked_at = ?,
                    revision = revision + 1,
                    updated_at = ?
                WHERE tenant_id = ?
                  AND id = ?
                  AND revision = ?
                  AND state = 'ACTIVE'
                """,
                revokedByIdentityId,
                Timestamp.from(now),
                Timestamp.from(now),
                tenant.tenantId(),
                operationId,
                expectedRevision);
        if (updated != 1) {
            throw new StaleWriteException(
                    "administrative-break-glass", operationId, expectedRevision);
        }
        return find(tenant, operationId).orElseThrow();
    }

    @Override
    public List<AdministrativeBreakGlassOperation> list(
            TenantContext tenant,
            Instant afterCreatedAt,
            UUID afterId,
            int limit) {
        if (afterCreatedAt == null && afterId == null) {
            return jdbc.query(
                    selectOperation()
                            + " WHERE b.tenant_id = ? ORDER BY b.created_at, b.id LIMIT ?",
                    (rs, rowNum) -> operationRow(rs),
                    tenant.tenantId(),
                    limit);
        }
        if (afterCreatedAt == null || afterId == null) {
            throw new IllegalArgumentException(
                    "both continuation values are required");
        }
        return jdbc.query(
                selectOperation()
                        + " WHERE b.tenant_id = ?"
                        + " AND (b.created_at, b.id) > (?, ?)"
                        + " ORDER BY b.created_at, b.id LIMIT ?",
                (rs, rowNum) -> operationRow(rs),
                tenant.tenantId(),
                Timestamp.from(afterCreatedAt),
                afterId,
                limit);
    }

    @Override
    public List<AdministrativeBreakGlassObligation> listObligations(
            TenantContext tenant, UUID operationId) {
        return jdbc.query(
                """
                SELECT o.id, o.break_glass_operation_id, o.obligation_type,
                       o.state, o.completed_at, o.revision, o.created_at, o.updated_at
                FROM administration.administrative_break_glass_obligation o
                WHERE o.tenant_id = ? AND o.break_glass_operation_id = ?
                ORDER BY o.obligation_type, o.id
                """,
                (rs, rowNum) -> new AdministrativeBreakGlassObligation(
                        rs.getObject("id", UUID.class),
                        rs.getObject("break_glass_operation_id", UUID.class),
                        AdministrativeBreakGlassObligationType.valueOf(
                                rs.getString("obligation_type")),
                        AdministrativeBreakGlassObligationState.valueOf(
                                rs.getString("state")),
                        instant(rs.getTimestamp("completed_at")),
                        rs.getLong("revision"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                tenant.tenantId(),
                operationId);
    }

    private void insertObligation(
            TenantContext tenant,
            UUID obligationId,
            UUID operationId,
            AdministrativeBreakGlassObligationType type,
            Instant now) {
        jdbc.update(
                """
                INSERT INTO administration.administrative_break_glass_obligation (
                    id, tenant_id, break_glass_operation_id,
                    obligation_type, state, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'PENDING', 1, ?, ?)
                """,
                obligationId,
                tenant.tenantId(),
                operationId,
                type.name(),
                Timestamp.from(now),
                Timestamp.from(now));
    }

    private static String selectOperation() {
        return """
                SELECT b.id, b.actor_identity_id, b.role_id,
                       b.scope_type, b.scope_resource_type, b.scope_ref_id, b.scope_key,
                       b.reason, b.incident_reference,
                       b.valid_from, b.valid_until,
                       b.activation_assurance_level,
                       b.activation_authenticated_at,
                       b.activation_step_up_at,
                       b.max_assurance_age_seconds,
                       b.state, b.activated_at,
                       b.revoked_by_identity_id, b.revoked_at,
                       b.correlation_id, b.causation_id,
                       b.revision, b.created_at, b.updated_at
                FROM administration.administrative_break_glass_operation b
                """;
    }

    private static AdministrativeBreakGlassOperation operationRow(
            java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AdministrativeBreakGlassOperation(
                rs.getObject("id", UUID.class),
                rs.getObject("actor_identity_id", UUID.class),
                rs.getObject("role_id", UUID.class),
                new AdministrativeScope(
                        AdministrativeScopeType.valueOf(rs.getString("scope_type")),
                        rs.getString("scope_resource_type"),
                        rs.getObject("scope_ref_id", UUID.class),
                        rs.getString("scope_key")),
                rs.getString("reason"),
                rs.getString("incident_reference"),
                rs.getTimestamp("valid_from").toInstant(),
                rs.getTimestamp("valid_until").toInstant(),
                AuthenticationAssuranceLevel.valueOf(
                        rs.getString("activation_assurance_level")),
                instant(rs.getTimestamp("activation_authenticated_at")),
                rs.getTimestamp("activation_step_up_at").toInstant(),
                rs.getLong("max_assurance_age_seconds"),
                AdministrativeBreakGlassState.valueOf(rs.getString("state")),
                rs.getTimestamp("activated_at").toInstant(),
                rs.getObject("revoked_by_identity_id", UUID.class),
                instant(rs.getTimestamp("revoked_at")),
                rs.getObject("correlation_id", UUID.class),
                rs.getObject("causation_id", UUID.class),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static Timestamp ts(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
