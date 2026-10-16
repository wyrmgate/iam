package io.wyrmgate.iam.platform.persistence;

import io.wyrmgate.iam.platform.tenant.PlatformTenantOverviewQuery;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC read adapter for canonical Platform-owned Tenant metadata. */
@Repository
public final class JdbcPlatformTenantOverviewQuery implements PlatformTenantOverviewQuery {
    private final JdbcTemplate jdbc;

    public JdbcPlatformTenantOverviewQuery(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<TenantSummary> find(UUID tenantId) {
        List<TenantSummary> rows = jdbc.query(
                """
                SELECT id, display_name, revision, created_at, updated_at
                FROM platform.tenant
                WHERE id = ?
                """,
                (rs, rowNum) -> new TenantSummary(
                        rs.getObject("id", UUID.class),
                        rs.getString("display_name"),
                        rs.getLong("revision"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                tenantId);
        return rows.stream().findFirst();
    }
}
