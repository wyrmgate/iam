package io.wyrmgate.iam.identity.persistence;

import io.wyrmgate.iam.identity.application.IdentityAccessPresentationQuery;
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

/** JDBC adapter for bounded Identity/Principal labels used by authorized cross-capability views. */
@Repository
public class JdbcIdentityAccessPresentationQuery implements IdentityAccessPresentationQuery {
    private static final int MAX_REFERENCES = 200;

    private final JdbcTemplate jdbc;

    public JdbcIdentityAccessPresentationQuery(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Labels labels(TenantContext tenant, Set<UUID> identityIds, Set<UUID> principalIds) {
        Objects.requireNonNull(tenant, "tenant");
        requireBounded(identityIds, "identityIds");
        requireBounded(principalIds, "principalIds");
        return new Labels(
                readLabels(tenant, identityIds, "identity.identity", "display_name"),
                readLabels(tenant, principalIds, "identity.principal", "native_principal_key"));
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
