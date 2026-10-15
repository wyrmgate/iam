package io.wyrmgate.iam.access.persistence;

import io.wyrmgate.iam.access.application.AccessQueryModels.EffectiveSupport;
import io.wyrmgate.iam.access.application.AccessQueryModels.EffectiveSupportPosition;
import io.wyrmgate.iam.access.application.EffectiveAccessSupportQuery;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public final class JdbcEffectiveAccessSupportQuery implements EffectiveAccessSupportQuery {
    private final JdbcTemplate jdbc;

    public JdbcEffectiveAccessSupportQuery(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public List<EffectiveSupport> page(
            TenantContext tenant,
            UUID effectiveAccessId,
            EffectiveSupportPosition after,
            int limit,
            Instant at) {
        if (limit < 1 || limit > 101) {
            throw new IllegalArgumentException("support page limit must be between 1 and 101");
        }
        record Base(UUID supportId, UUID assignmentId, String pathHash, int pathDepth) {}
        String afterClause = after == null
                ? ""
                : " AND (s.access_assignment_id, s.path_hash) > (?, ?) ";
        String sql = """
                SELECT s.id, s.access_assignment_id, s.path_hash, s.path_depth
                FROM access.effective_access_support s
                JOIN access.access_assignment a
                  ON a.tenant_id = s.tenant_id
                 AND a.id = s.access_assignment_id
                WHERE s.tenant_id = ?
                  AND s.effective_access_id = ?
                  AND a.lifecycle_state NOT IN ('SUSPENDED', 'REVOKED', 'EXPIRED', 'CANCELLED')
                  AND (a.valid_from IS NULL OR a.valid_from <= ?)
                  AND (a.valid_until IS NULL OR a.valid_until > ?)
                """ + afterClause + " ORDER BY s.access_assignment_id, s.path_hash LIMIT ?";
        List<Object> args = new ArrayList<>();
        args.add(tenant.tenantId());
        args.add(effectiveAccessId);
        args.add(Timestamp.from(at));
        args.add(Timestamp.from(at));
        if (after != null) {
            args.add(after.accessAssignmentId());
            args.add(after.pathHash());
        }
        args.add(limit);
        List<Base> bases = jdbc.query(sql, (rs, row) -> new Base(
                rs.getObject("id", UUID.class),
                rs.getObject("access_assignment_id", UUID.class),
                rs.getString("path_hash"),
                rs.getInt("path_depth")), args.toArray());
        List<EffectiveSupport> result = new ArrayList<>(bases.size());
        for (Base base : bases) {
            List<UUID> roleVersions = jdbc.query("""
                    SELECT role_version_id
                    FROM access.effective_access_support_role_version
                    WHERE tenant_id = ?
                      AND effective_access_support_id = ?
                    ORDER BY path_ordinal
                    """,
                    (rs, row) -> rs.getObject("role_version_id", UUID.class),
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

    @Override
    public int countCurrent(
            TenantContext tenant,
            UUID effectiveAccessId,
            Instant at) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*)
                FROM access.effective_access_support s
                JOIN access.access_assignment a
                  ON a.tenant_id = s.tenant_id
                 AND a.id = s.access_assignment_id
                WHERE s.tenant_id = ?
                  AND s.effective_access_id = ?
                  AND a.lifecycle_state NOT IN ('SUSPENDED', 'REVOKED', 'EXPIRED', 'CANCELLED')
                  AND (a.valid_from IS NULL OR a.valid_from <= ?)
                  AND (a.valid_until IS NULL OR a.valid_until > ?)
                """,
                Integer.class,
                tenant.tenantId(),
                effectiveAccessId,
                Timestamp.from(at),
                Timestamp.from(at));
        return count == null ? 0 : count;
    }
}
