package io.wyrmgate.iam.identity.persistence;

import io.wyrmgate.iam.identity.application.IdentityOperationsOverviewQuery;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcIdentityOperationsOverviewQuery implements IdentityOperationsOverviewQuery {
    private final JdbcTemplate jdbc;

    public JdbcIdentityOperationsOverviewQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long activeIdentities(TenantContext tenant) {
        Long value = jdbc.queryForObject(
                "SELECT count(*) FROM identity.identity WHERE tenant_id = ? AND lifecycle_state = 'ACTIVE'",
                Long.class,
                tenant.tenantId());
        return value == null ? 0 : value;
    }

    @Override
    public long uncorrelatedPrincipals(TenantContext tenant) {
        Long value = jdbc.queryForObject(
                "SELECT count(*) FROM identity.principal WHERE tenant_id = ? AND lifecycle_state = 'ACTIVE' AND identity_id IS NULL",
                Long.class,
                tenant.tenantId());
        return value == null ? 0 : value;
    }

    @Override
    public List<IdentityTypeCount> activeIdentityTypes(TenantContext tenant) {
        return jdbc.query(
                """
                SELECT identity_type, count(*) AS total
                FROM identity.identity
                WHERE tenant_id = ? AND lifecycle_state = 'ACTIVE'
                GROUP BY identity_type
                ORDER BY identity_type
                """,
                (rs, rowNum) -> new IdentityTypeCount(rs.getString("identity_type"), rs.getLong("total")),
                tenant.tenantId());
    }

    @Override
    public SourceSystems sourceSystems(TenantContext tenant, int limit) {
        if (limit < 1 || limit > 20) {
            throw new IllegalArgumentException("source-system overview limit must be between 1 and 20");
        }
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM identity.source_system WHERE tenant_id = ?",
                Long.class,
                tenant.tenantId());
        List<SourceSystemSummary> items = jdbc.query(
                """
                SELECT s.id, s.code, s.name,
                       count(DISTINCT l.identity_id) FILTER (WHERE l.link_state = 'ACCEPTED') AS linked_identity_count
                FROM identity.source_system s
                LEFT JOIN identity.source_record r
                  ON r.tenant_id = s.tenant_id AND r.source_system_id = s.id
                LEFT JOIN identity.identity_link l
                  ON l.tenant_id = r.tenant_id AND l.source_record_id = r.id
                WHERE s.tenant_id = ?
                GROUP BY s.id, s.code, s.name
                ORDER BY s.name, s.id
                LIMIT ?
                """,
                (rs, rowNum) -> new SourceSystemSummary(
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getLong("linked_identity_count")),
                tenant.tenantId(),
                limit);
        return new SourceSystems(total == null ? 0 : total, items);
    }

    @Override
    public Optional<String> identityDisplayName(TenantContext tenant, UUID identityId) {
        return jdbc.query(
                """
                SELECT display_name
                FROM identity.identity
                WHERE tenant_id = ? AND id = ?
                """,
                (rs, rowNum) -> rs.getString("display_name"),
                tenant.tenantId(),
                identityId).stream().findFirst();
    }
}
