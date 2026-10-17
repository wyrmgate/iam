package io.wyrmgate.iam.catalog.persistence;

import io.wyrmgate.iam.catalog.application.CatalogOperationsOverviewQuery;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

    @Override
    public Map<UUID, ApplicationAccessLabel> applicationsForEntitlements(
            TenantContext tenant,
            Set<UUID> entitlementIds) {
        if (entitlementIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(entitlementIds.size(), "?"));
        Object[] args = new Object[entitlementIds.size() + 1];
        args[0] = tenant.tenantId();
        int index = 1;
        for (UUID id : entitlementIds) args[index++] = id;
        Map<UUID, ApplicationAccessLabel> result = new HashMap<>();
        jdbc.query(
                "SELECT e.id AS entitlement_id, a.id AS application_id, a.name AS application_name "
                        + "FROM catalog.entitlement e JOIN catalog.application a "
                        + "ON a.tenant_id = e.tenant_id AND a.id = e.application_id "
                        + "WHERE e.tenant_id = ? AND e.id IN (" + placeholders + ")",
                rs -> result.put(
                        rs.getObject("entitlement_id", UUID.class),
                        new ApplicationAccessLabel(
                                rs.getObject("application_id", UUID.class),
                                rs.getString("application_name"))),
                args);
        return Map.copyOf(result);
    }
}
