package io.wyrmgate.iam.access.persistence;

import io.wyrmgate.iam.access.application.AccessQueryModels.EffectiveSupport;
import io.wyrmgate.iam.access.application.EffectiveAccessRepository;
import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.access.domain.EffectiveAccess;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
                    Timestamp.from(computedAt));
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
                    Timestamp.from(computedAt),
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
                SELECT effective_access_id
                FROM access.effective_access_support
                WHERE tenant_id = ? AND access_assignment_id = ?
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
                        Timestamp.from(computedAt),
                        tenant.tenantId(),
                        effectiveId);
            }
        }
    }

    @Override
    public List<UUID> replaceRoleAssignmentSupport(
            TenantContext tenant,
            AccessAssignment assignment,
            String principalConstraintKey,
            List<RoleSupportPath> paths,
            Instant computedAt) {
        record Existing(
                UUID supportId,
                UUID effectiveAccessId,
                UUID entitlementId,
                String pathHash) {}

        List<Existing> existing = jdbc.query("""
                SELECT s.id, s.effective_access_id, ea.entitlement_id, s.path_hash
                FROM access.effective_access_support s
                JOIN access.effective_access ea
                  ON ea.tenant_id = s.tenant_id
                 AND ea.id = s.effective_access_id
                WHERE s.tenant_id = ? AND s.access_assignment_id = ?
                """,
                (rs,row) -> new Existing(
                        rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class),
                        rs.getString(4)),
                tenant.tenantId(),
                assignment.id());

        Map<String,RoleSupportPath> desired = new LinkedHashMap<>();
        for (RoleSupportPath path : paths) {
            String key = path.entitlementId() + "|" + path.pathHash();
            if (desired.putIfAbsent(key, path) != null) {
                throw new IllegalArgumentException(
                        "duplicate role support path " + key);
            }
        }

        Set<UUID> touchedEntitlements = new LinkedHashSet<>();
        Set<UUID> changedEffective = new LinkedHashSet<>();
        Set<UUID> createdEffective = new LinkedHashSet<>();
        Set<String> existingKeys = new LinkedHashSet<>();

        for (Existing current : existing) {
            String key = current.entitlementId() + "|" + current.pathHash();
            touchedEntitlements.add(current.entitlementId());
            if (desired.containsKey(key)) {
                existingKeys.add(key);
                continue;
            }
            jdbc.update("""
                    DELETE FROM access.effective_access_support
                    WHERE tenant_id = ? AND id = ?
                    """,
                    tenant.tenantId(), current.supportId());
            changedEffective.add(current.effectiveAccessId());
        }

        for (Map.Entry<String,RoleSupportPath> entry : desired.entrySet()) {
            RoleSupportPath path = entry.getValue();
            touchedEntitlements.add(path.entitlementId());
            if (existingKeys.contains(entry.getKey())) continue;

            UUID effectiveId = findTupleId(
                            tenant,
                            assignment.identityId(),
                            path.entitlementId(),
                            principalConstraintKey)
                    .orElse(null);
            if (effectiveId == null) {
                UUID candidate = ids.nextId();
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
                        candidate,
                        tenant.tenantId(),
                        assignment.identityId(),
                        path.entitlementId(),
                        principalConstraintKey,
                        Timestamp.from(computedAt));
                if (inserted == 1) {
                    effectiveId = candidate;
                    createdEffective.add(effectiveId);
                } else {
                    effectiveId = findTupleId(
                                    tenant,
                                    assignment.identityId(),
                                    path.entitlementId(),
                                    principalConstraintKey)
                            .orElseThrow();
                }
            }

            UUID supportId = ids.nextId();
            int supportInserted = jdbc.update("""
                    INSERT INTO access.effective_access_support (
                        id, tenant_id, effective_access_id,
                        access_assignment_id, role_version_id,
                        path_hash, path_depth)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (
                        tenant_id, effective_access_id,
                        access_assignment_id, path_hash)
                    DO NOTHING
                    """,
                    supportId,
                    tenant.tenantId(),
                    effectiveId,
                    assignment.id(),
                    path.roleVersionPath().getFirst(),
                    path.pathHash(),
                    path.roleVersionPath().size());
            if (supportInserted == 1) {
                for (int ordinal = 0;
                        ordinal < path.roleVersionPath().size();
                        ordinal++) {
                    jdbc.update("""
                            INSERT INTO access.effective_access_support_role_version (
                                tenant_id, effective_access_support_id,
                                path_ordinal, role_version_id)
                            VALUES (?, ?, ?, ?)
                            """,
                            tenant.tenantId(),
                            supportId,
                            ordinal,
                            path.roleVersionPath().get(ordinal));
                }
                changedEffective.add(effectiveId);
            }
        }

        for (UUID effectiveId : changedEffective) {
            Integer remaining = jdbc.queryForObject("""
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
                        tenant.tenantId(), effectiveId);
            } else if (createdEffective.contains(effectiveId)) {
                jdbc.update("""
                        UPDATE access.effective_access
                        SET support_count = ?, computed_at = ?
                        WHERE tenant_id = ? AND id = ?
                        """,
                        count,
                        Timestamp.from(computedAt),
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
                        Timestamp.from(computedAt),
                        tenant.tenantId(),
                        effectiveId);
            }
        }

        return List.copyOf(touchedEntitlements);
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
                Timestamp.from(at),
                Timestamp.from(at))
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
                Timestamp.from(at),
                Timestamp.from(at));
    }

    @Override
    public List<EffectiveAccess> findCurrentPage(
            TenantContext tenant,
            UUID identityId,
            UUID afterId,
            int limit,
            Instant at) {
        String identityClause = identityId == null
                ? ""
                : " AND ea.identity_id = ? ";
        String afterClause = afterId == null
                ? ""
                : " AND ea.id > ? ";
        String sql = """
                SELECT ea.id, ea.identity_id, ea.entitlement_id,
                       ea.principal_constraint_key, ea.support_count,
                       ea.computed_at, ea.projection_generation
                FROM access.effective_access ea
                WHERE ea.tenant_id = ?
                """ + identityClause + afterClause + """
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
                ORDER BY ea.id
                LIMIT ?
                """;
        List<Object> args = new java.util.ArrayList<>();
        args.add(tenant.tenantId());
        if (identityId != null) args.add(identityId);
        if (afterId != null) args.add(afterId);
        args.add(Timestamp.from(at));
        args.add(Timestamp.from(at));
        args.add(limit);
        return jdbc.query(
                sql,
                (rs,row) -> new EffectiveAccess(
                        rs.getObject("id", UUID.class),
                        rs.getObject("identity_id", UUID.class),
                        rs.getObject("entitlement_id", UUID.class),
                        rs.getString("principal_constraint_key"),
                        rs.getInt("support_count"),
                        rs.getTimestamp("computed_at").toInstant(),
                        rs.getLong("projection_generation")),
                args.toArray());
    }

    @Override
    public Optional<EffectiveAccess> findCurrentById(
            TenantContext tenant,
            UUID effectiveAccessId,
            Instant at) {
        return jdbc.query("""
                SELECT ea.id, ea.identity_id, ea.entitlement_id,
                       ea.principal_constraint_key, ea.support_count,
                       ea.computed_at, ea.projection_generation
                FROM access.effective_access ea
                WHERE ea.tenant_id = ?
                  AND ea.id = ?
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
                effectiveAccessId,
                Timestamp.from(at),
                Timestamp.from(at))
                .stream()
                .findFirst();
    }

    @Override
    public List<EffectiveSupport> currentSupportEvidence(
            TenantContext tenant,
            UUID effectiveAccessId,
            Instant at) {
        record Base(
                UUID supportId,
                UUID assignmentId,
                String pathHash,
                int pathDepth) {}
        List<Base> bases = jdbc.query("""
                SELECT s.id, s.access_assignment_id, s.path_hash, s.path_depth
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
                ORDER BY s.access_assignment_id, s.path_hash
                """,
                (rs,row) -> new Base(
                        rs.getObject("id", UUID.class),
                        rs.getObject("access_assignment_id", UUID.class),
                        rs.getString("path_hash"),
                        rs.getInt("path_depth")),
                tenant.tenantId(),
                effectiveAccessId,
                Timestamp.from(at),
                Timestamp.from(at));
        List<EffectiveSupport> result = new java.util.ArrayList<>();
        for (Base base : bases) {
            List<UUID> roleVersions = jdbc.query("""
                    SELECT role_version_id
                    FROM access.effective_access_support_role_version
                    WHERE tenant_id = ?
                      AND effective_access_support_id = ?
                    ORDER BY path_ordinal
                    """,
                    (rs,row) -> rs.getObject("role_version_id", UUID.class),
                    tenant.tenantId(),
                    base.supportId());
            result.add(new EffectiveSupport(
                    base.assignmentId(),
                    base.pathHash(),
                    base.pathDepth(),
                    roleVersions));
        }
        return List.copyOf(result);
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
