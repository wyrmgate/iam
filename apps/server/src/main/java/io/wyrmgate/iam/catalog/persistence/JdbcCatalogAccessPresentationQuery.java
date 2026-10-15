package io.wyrmgate.iam.catalog.persistence;

import io.wyrmgate.iam.catalog.application.CatalogAccessPresentationQuery;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC adapter for bounded Role/Entitlement labels used by authorized cross-capability views. */
@Repository
public final class JdbcCatalogAccessPresentationQuery implements CatalogAccessPresentationQuery {
    private static final int MAX_REFERENCES = 200;

    private final JdbcTemplate jdbc;

    public JdbcCatalogAccessPresentationQuery(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Labels labels(TenantContext tenant, Set<UUID> roleIds, Set<UUID> entitlementIds) {
        Objects.requireNonNull(tenant, "tenant");
        requireBounded(roleIds, "roleIds");
        requireBounded(entitlementIds, "entitlementIds");
        return new Labels(
                readLabels(tenant, roleIds, "catalog.role", "name"),
                readLabels(tenant, entitlementIds, "catalog.entitlement", "code"));
    }

    private Map<UUID, String> readLabels(
            TenantContext tenant,
            Set<UUID> ids,
            String table,
            String labelColumn) {
        if (ids == null || ids.isEmpty()) return Map.of();
        String placeholders = String.join(", ", Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>(ids.size() + 1);
        args.add(tenant.tenantId());
        args.addAll(ids);
        String sql = "SELECT id, " + labelColumn + " AS label FROM " + table
                + " WHERE tenant_id = ? AND id IN (" + placeholders + ")";
        return jdbc.query(sql, rs -> {
            Map<UUID, String> labels = new LinkedHashMap<>();
            while (rs.next()) {
                labels.put(rs.getObject("id", UUID.class), rs.getString("label"));
            }
            return labels;
        }, args.toArray());
    }

    private static void requireBounded(Set<UUID> ids, String name) {
        Objects.requireNonNull(ids, name);
        if (ids.size() > MAX_REFERENCES) {
            throw new IllegalArgumentException(name + " must contain at most " + MAX_REFERENCES + " values");
        }
    }
}
