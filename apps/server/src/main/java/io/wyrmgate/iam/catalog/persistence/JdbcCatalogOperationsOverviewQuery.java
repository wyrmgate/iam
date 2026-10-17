package io.wyrmgate.iam.catalog.persistence;

import io.wyrmgate.iam.catalog.application.CatalogOperationsOverviewQuery;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCatalogOperationsOverviewQuery implements CatalogOperationsOverviewQuery {
    private final JdbcTemplate jdbc;

    public JdbcCatalogOperationsOverviewQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long activeApplications(TenantContext tenant) {
        Long value = jdbc.queryForObject(
                "SELECT count(*) FROM catalog.application WHERE tenant_id = ? AND lifecycle_state = 'ACTIVE'",
                Long.class,
                tenant.tenantId());
        return value == null ? 0 : value;
    }

    @Override
    public List<ApplicationOriginCount> activeApplicationOrigins(TenantContext tenant) {
        return jdbc.query(
                """
                SELECT COALESCE(offering.tenancy_mode, 'TENANT_LOCAL') AS origin, count(*) AS total
                FROM catalog.application app
                LEFT JOIN catalog.platform_application_binding binding
                  ON binding.tenant_id = app.tenant_id
                 AND binding.application_id = app.id
                 AND binding.lifecycle_state = 'ACTIVE'
                LEFT JOIN catalog.platform_application_offering offering
                  ON offering.id = binding.platform_application_id
                 AND offering.lifecycle_state = 'ACTIVE'
                WHERE app.tenant_id = ? AND app.lifecycle_state = 'ACTIVE'
                GROUP BY COALESCE(offering.tenancy_mode, 'TENANT_LOCAL')
                ORDER BY origin
                """,
                (rs, rowNum) -> new ApplicationOriginCount(rs.getString("origin"), rs.getLong("total")),
                tenant.tenantId());
    }
}
