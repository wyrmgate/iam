package io.wyrmgate.iam.access.persistence;

import io.wyrmgate.iam.access.application.AccessOperationsOverviewQuery;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAccessOperationsOverviewQuery implements AccessOperationsOverviewQuery {
    private final JdbcTemplate jdbc;

    public JdbcAccessOperationsOverviewQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long activeAssignments(TenantContext tenant) {
        Long value = jdbc.queryForObject(
                "SELECT count(*) FROM access.access_assignment WHERE tenant_id = ? AND lifecycle_state = 'ACTIVE'",
                Long.class,
                tenant.tenantId());
        return value == null ? 0 : value;
    }

    @Override
    public List<EntitlementAccessCount> effectiveAccessByEntitlement(TenantContext tenant, int limit) {
        if (limit < 1 || limit > 20) {
            throw new IllegalArgumentException("effective-access overview limit must be between 1 and 20");
        }
        return jdbc.query(
                """
                SELECT entitlement_id, count(*) AS total
                FROM access.effective_access
                WHERE tenant_id = ?
                GROUP BY entitlement_id
                ORDER BY total DESC, entitlement_id
                LIMIT ?
                """,
                (rs, rowNum) -> new EntitlementAccessCount(
                        rs.getObject("entitlement_id", UUID.class),
                        rs.getLong("total")),
                tenant.tenantId(),
                limit);
    }
}
