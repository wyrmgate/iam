package io.wyrmgate.iam.catalog.persistence;

import io.wyrmgate.iam.catalog.application.CatalogAccessTargetSearchQuery;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public final class JdbcCatalogAccessTargetSearchQuery implements CatalogAccessTargetSearchQuery {
    private final JdbcTemplate jdbc;

    public JdbcCatalogAccessTargetSearchQuery(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public List<TargetOption> search(
            TenantContext tenant,
            TargetKind targetKind,
            String query,
            int limit) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(targetKind, "targetKind");
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("limit must be between 1 and 50");
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 200 || normalized.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("query must contain no NUL and be at most 200 characters");
        }
        String prefix = normalized + "%";
        if (targetKind == TargetKind.ROLE) {
            return jdbc.query("""
                    SELECT id, name, code, lifecycle_state
                    FROM catalog.role
                    WHERE tenant_id = ?
                      AND lifecycle_state = 'ACTIVE'
                      AND (? = '' OR lower(name) LIKE ? OR lower(code) LIKE ?)
                    ORDER BY lower(name), id
                    LIMIT ?
                    """,
                    (rs, row) -> new TargetOption(
                            rs.getObject("id", java.util.UUID.class),
                            TargetKind.ROLE,
                            rs.getString("name"),
                            rs.getString("code"),
                            rs.getString("lifecycle_state")),
                    tenant.tenantId(), normalized, prefix, prefix, limit);
        }
        return jdbc.query("""
                SELECT id, code, native_key, lifecycle_state
                FROM catalog.entitlement
                WHERE tenant_id = ?
                  AND lifecycle_state = 'ACTIVE'
                  AND (? = '' OR lower(code) LIKE ? OR lower(coalesce(native_key, '')) LIKE ?)
                ORDER BY lower(code), id
                LIMIT ?
                """,
                (rs, row) -> new TargetOption(
                        rs.getObject("id", java.util.UUID.class),
                        TargetKind.ENTITLEMENT,
                        rs.getString("code"),
                        rs.getString("code"),
                        rs.getString("lifecycle_state")),
                tenant.tenantId(), normalized, prefix, prefix, limit);
    }
}
