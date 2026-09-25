package io.wyrmgate.iam.access.persistence;

import io.wyrmgate.iam.access.application.EffectiveAccessRepository;
import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.access.domain.EffectiveAccess;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcValues;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcEffectiveAccessRepository
        implements EffectiveAccessRepository {

    private final JdbcTemplate jdbc;
    private final IdGenerator ids;

    public JdbcEffectiveAccessRepository(JdbcTemplate jdbc, IdGenerator ids) {
        this.jdbc = jdbc;
        this.ids = ids;
    }

    @Override
    public void applyDirectAssignment(
            TenantContext tenant,
            AccessAssignment assignment,
            String principalConstraintKey,
            String pathHash,
            Instant computedAt) {
        UUID effectiveId = findTupleId(
                        tenant,
                        assignment.identityId(),
                        assignment.entitlementId(),
                        principalConstraintKey)
                .orElse(null);
        boolean created = false;
        if (effectiveId == null) {
            UUID candidateId = ids.nextId();
            int inserted = jdbc.update("""
                    INSERT INTO access.effective_access (
                        id, tenant_id, identity_id, entitlement_id,
                        principal_constraint_key, support_count,
                        computed_at, projection_generation)
                    VALUES (?, ?, ?, ?, ?, 1, ?, 1)
                    ON CONFLICT (
                        tenant_id, identity_id, entitlement_id,
                        principal_constraint_key)
                    DO NOTHING
                    """,
                    candidateId,
                    tenant.tenantId(),
                    assignment.identityId(),
                    assignment.entitlementId(),
                    principalConstraintKey,
                    JdbcValues.timestamp(computedAt));
            created = inserted == 1;
            effectiveId = created
                    ? candidateId
                    : findTupleId(
                            tenant,
                            assignment.identityId(),
                            assignment.entitlementId(),
                            principalConstraintKey)
                            .orElseThrow();
        }

        int supportInserted = jdbc.update("""
                INSERT INTO access.effective_access_support (
                    id, tenant_id, effective_access_id,
                    access_assignment_id, role_version_id,
                    path_hash, path_depth)
                VALUES (?, ?, ?, ?, NULL, ?, 0)
                ON CONFLICT (
                    tenant_id, effective_access_id,
                    access_assignment_id, path_hash)
                DO NOTHING
                """,
                ids.nextId(),
                tenant.tenantId(),
                effectiveId,
                assignment.id(),
                pathHash);

        if (!created && supportInserted == 1) {
            jdbc.update("""
                    UPDATE access.effective_access
                    SET support_count = (
                            SELECT count(*)
                            FROM access.effective_access_support s
                            WHERE s.tenant_id = ?
                              AND s.effective_access_id = ?),
                        computed_at = ?,
                        projection_generation = projection_generation + 1
                    WHERE tenant_id = ? AND id = ?
                    """,
                    tenant.tenantId(),
                    effectiveId,
                    JdbcValues.timestamp(computedAt),
                    tenant.tenantId(),
                    effectiveId);
        }
    }

    @Override
    public void removeAssignmentSupport(
            TenantContext tenant,
            UUID assignmentId,
            Instant computedAt) {
        List<UUID> effectiveIds = jdbc.query(
                """
                SELECT DISTINCT effective_access_id
                FROM access.effective_access_support
                WHERE tenant_id = ? AND access_assignment_id = ?
                FOR UPDATE
                """,
                (rs, row) -> rs.getObject("effective_access_id", UUID.class),
                tenant.tenantId(),
                assignmentId);
        if (effectiveIds.isEmpty()) return;

        jdbc.update("""
                DELETE FROM access.effective_access_support
                WHERE tenant_id = ? AND access_assignment_id = ?
                """,
                tenant.tenantId(),
                assignmentId);

        for (UUID effectiveId : effectiveIds) {
            Integer remaining = jdbc.queryForObject(
                    """
                    SELECT count(*)
                    FROM access.effective_access_support
                    WHERE tenant_id = ? AND effective_access_id = ?
                    """,
                    Integer.class,
                    tenant.tenantId(),
                    effectiveId);
            int count = remaining == null ? 0 : remaining;
            if (count == 0) {
                jdbc.update("""
                        DELETE FROM access.effective_access
                        WHERE tenant_id = ? AND id = ?
                        """,
                        tenant.tenantId(),
                        effectiveId);
            } else {
                jdbc.update("""
                        UPDATE access.effective_access
                        SET support_count = ?,
                            computed_at = ?,
                            projection_generation = projection_generation + 1
                        WHERE tenant_id = ? AND id = ?
                        """,
                        count,
                        JdbcValues.timestamp(computedAt),
                        tenant.tenantId(),
                        effectiveId);
            }
        }
    }

    @Override
    public Optional<EffectiveAccess> findCurrent(
            TenantContext tenant,
            UUID identityId,
            UUID entitlementId,
            String principalConstraintKey,
            Instant at) {
        return jdbc.query("""
                SELECT ea.id, ea.identity_id, ea.entitlement_id,
                       ea.principal_constraint_key, ea.support_count,
                       ea.computed_at, ea.projection_generation
                FROM access.effective_access ea
                WHERE ea.tenant_id = ?
                  AND ea.identity_id = ?
                  AND ea.entitlement_id = ?
                  AND ea.principal_constraint_key = ?
                  AND EXISTS (
                      SELECT 1
                      FROM access.effective_access_support s
                      JOIN access.access_assignment a
                        ON a.tenant_id = s.tenant_id
                       AND a.id = s.access_assignment_id
                      WHERE s.tenant_id = ea.tenant_id
                        AND s.effective_access_id = ea.id
                        AND a.lifecycle_state NOT IN (
                            'SUSPENDED', 'REVOKED', 'EXPIRED', 'CANCELLED')
                        AND (a.valid_from IS NULL OR a.valid_from <= ?)
                        AND (a.valid_until IS NULL OR a.valid_until > ?)
                  )
                """,
                (rs,row) -> new EffectiveAccess(
                        rs.getObject("id", UUID.class),
                        rs.getObject("identity_id", UUID.class),
                        rs.getObject("entitlement_id", UUID.class),
                        rs.getString("principal_constraint_key"),
                        rs.getInt("support_count"),
                        rs.getTimestamp("computed_at").toInstant(),
                        rs.getLong("projection_generation")),
                tenant.tenantId(),
                identityId,
                entitlementId,
                principalConstraintKey,
                JdbcValues.timestamp(at),
                JdbcValues.timestamp(at))
                .stream()
                .findFirst();
    }

    @Override
    public List<UUID> currentSupportingAssignmentIds(
            TenantContext tenant,
            UUID effectiveAccessId,
            Instant at) {
        return jdbc.query("""
                SELECT s.access_assignment_id
                FROM access.effective_access_support s
                JOIN access.access_assignment a
                  ON a.tenant_id = s.tenant_id
                 AND a.id = s.access_assignment_id
                WHERE s.tenant_id = ?
                  AND s.effective_access_id = ?
                  AND a.lifecycle_state NOT IN (
                      'SUSPENDED', 'REVOKED', 'EXPIRED', 'CANCELLED')
                  AND (a.valid_from IS NULL OR a.valid_from <= ?)
                  AND (a.valid_until IS NULL OR a.valid_until > ?)
                ORDER BY s.access_assignment_id
                """,
                (rs,row) -> rs.getObject("access_assignment_id", UUID.class),
                tenant.tenantId(),
                effectiveAccessId,
                JdbcValues.timestamp(at),
                JdbcValues.timestamp(at));
    }

    private Optional<UUID> findTupleId(
            TenantContext tenant,
            UUID identityId,
            UUID entitlementId,
            String principalConstraintKey) {
        return jdbc.query("""
                SELECT id
                FROM access.effective_access
                WHERE tenant_id = ?
                  AND identity_id = ?
                  AND entitlement_id = ?
                  AND principal_constraint_key = ?
                """,
                (rs,row) -> rs.getObject("id", UUID.class),
                tenant.tenantId(),
                identityId,
                entitlementId,
                principalConstraintKey)
                .stream()
                .findFirst();
    }
}
