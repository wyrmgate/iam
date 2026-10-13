package io.wyrmgate.iam.identity.persistence;
import io.wyrmgate.iam.identity.application.IdentityOperationsOverviewQuery;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
@Repository
public class JdbcIdentityOperationsOverviewQuery implements IdentityOperationsOverviewQuery {
    private final JdbcTemplate jdbc;
    public JdbcIdentityOperationsOverviewQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public long activeIdentities(TenantContext tenant) {
        Long value = jdbc.queryForObject("SELECT count(*) FROM identity.identity WHERE tenant_id = ? AND lifecycle_state = 'ACTIVE'", Long.class, tenant.tenantId());
        return value == null ? 0 : value;
    }
    @Override public long uncorrelatedPrincipals(TenantContext tenant) {
        Long value = jdbc.queryForObject("SELECT count(*) FROM identity.principal WHERE tenant_id = ? AND lifecycle_state = 'ACTIVE' AND identity_id IS NULL", Long.class, tenant.tenantId());
        return value == null ? 0 : value;
    }
}
