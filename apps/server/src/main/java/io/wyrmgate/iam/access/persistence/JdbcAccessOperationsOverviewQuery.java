package io.wyrmgate.iam.access.persistence;

import io.wyrmgate.iam.access.application.AccessOperationsOverviewQuery;
import io.wyrmgate.iam.platform.tenant.TenantContext;
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
}
