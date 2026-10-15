package io.wyrmgate.iam.catalog.persistence;

import io.wyrmgate.iam.catalog.application.CatalogAccessTargetSearchQuery;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

final class JdbcCatalogAccessTargetSearchQuery implements CatalogAccessTargetSearchQuery {
    private final JdbcTemplate jdbc;

    JdbcCatalogAccessTargetSearchQuery(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public List<Result> search(TenantContext tenant, Kind kind, String query, int limit) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(kind, "kind");
        if (limit < 1 || limit > 20) throw new IllegalArgumentException("limit must be between 1 and 20");
        String normalized = query == null ? "" : query.trim();
        if (normalized.length() > 200 || normalized.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("query must contain no NUL character and be at most 200 characters");
        }
        String pattern = "%" + escapeLike(normalized.toLowerCase()) + "%";
        return switch (kind) {
            case ROLE -> jdbc.query("""
                    SELECT id, name, code, type
                    FROM catalog.role
                    WHERE tenant_id = ? AND lifecycle_state = 'ACTIVE'
                      AND (LOWER(name) LIKE ? ESCAPE '\\' OR LOWER(code) LIKE ? ESCAPE '\\')
                    ORDER BY LOWER(name), code, id
                    LIMIT ?
                    """, (rs,row) -> new Result(
                            rs.getObject("id", java.util.UUID.class), Kind.ROLE,
                            rs.getString("name"), rs.getString("code"), rs.getString("type")),
                    tenant.tenantId(), pattern, pattern, limit);
            case ENTITLEMENT -> jdbc.query("""
                    SELECT id, code, native_key, entitlement_type
                    FROM catalog.entitlement
                    WHERE tenant_id = ? AND lifecycle_state = 'ACTIVE'
                      AND (LOWER(code) LIKE ? ESCAPE '\\'
                           OR LOWER(COALESCE(native_key, '')) LIKE ? ESCAPE '\\')
                    ORDER BY LOWER(code), id
                    LIMIT ?
                    """, (rs,row) -> new Result(
                            rs.getObject("id", java.util.UUID.class), Kind.ENTITLEMENT,
                            rs.getString("code"), rs.getString("code"), rs.getString("entitlement_type")),
                    tenant.tenantId(), pattern, pattern, limit);
        };
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
