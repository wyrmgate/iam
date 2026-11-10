package io.wyrmgate.iam.tenancy.persistence;

import io.wyrmgate.iam.tenancy.application.TenantOverviewQuery;
import io.wyrmgate.iam.tenancy.domain.TenantLifecycleState;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC-backed Tenancy inventory projection. */
public final class JdbcTenantOverviewQuery implements TenantOverviewQuery {

    private final JdbcTemplate jdbc;

    public JdbcTenantOverviewQuery(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<TenantSummary> find(UUID tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        List<TenantSummary> rows = jdbc.query(
                """
                SELECT id, display_name, lifecycle_state, revision, created_at, updated_at
                FROM tenancy.tenant
                WHERE id = ?
                """,
                (rs, rowNum) -> map(rs),
                tenantId);
        return rows.stream().findFirst();
    }

    @Override
    public List<TenantSummary> listAfter(Instant createdAt, UUID id, int limit) {
        if ((createdAt == null) != (id == null)) {
            throw new IllegalArgumentException(
                    "createdAt and id must both be null or both be supplied");
        }
        if (limit < 1 || limit > 101) {
            throw new IllegalArgumentException("limit must be between 1 and 101");
        }
        if (createdAt == null) {
            return jdbc.query(
                    """
                    SELECT id, display_name, lifecycle_state, revision, created_at, updated_at
                    FROM tenancy.tenant
                    ORDER BY created_at ASC, id ASC
                    LIMIT ?
                    """,
                    (rs, rowNum) -> map(rs),
                    limit);
        }
        return jdbc.query(
                """
                SELECT id, display_name, lifecycle_state, revision, created_at, updated_at
                FROM tenancy.tenant
                WHERE created_at > ? OR (created_at = ? AND id > ?)
                ORDER BY created_at ASC, id ASC
                LIMIT ?
                """,
                (rs, rowNum) -> map(rs),
                Timestamp.from(createdAt),
                Timestamp.from(createdAt),
                id,
                limit);
    }

    private static TenantSummary map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TenantSummary(
                rs.getObject("id", UUID.class),
                rs.getString("display_name"),
                TenantLifecycleState.valueOf(rs.getString("lifecycle_state")),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
