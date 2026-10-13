package io.wyrmgate.iam.catalog.persistence;
import io.wyrmgate.iam.catalog.application.CatalogOperationsOverviewQuery;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
@Repository
public final class JdbcCatalogOperationsOverviewQuery implements CatalogOperationsOverviewQuery {
    private final JdbcTemplate jdbc;
    public JdbcCatalogOperationsOverviewQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public long activeApplications(TenantContext tenant) {
        Long value = jdbc.queryForObject("SELECT count(*) FROM catalog.application WHERE tenant_id = ? AND lifecycle_state = 'ACTIVE'", Long.class, tenant.tenantId());
        return value == null ? 0 : value;
    }
}
