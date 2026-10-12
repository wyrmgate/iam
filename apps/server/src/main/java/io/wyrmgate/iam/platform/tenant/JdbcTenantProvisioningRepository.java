package io.wyrmgate.iam.platform.tenant;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC adapter for Platform-owned tenant isolation roots. */
public final class JdbcTenantProvisioningRepository implements TenantProvisioningRepository {

    private final JdbcTemplate jdbc;

    public JdbcTenantProvisioningRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public long countTenants() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM platform.tenant", Long.class);
        return count == null ? 0L : count;
    }

    @Override
    public Optional<TenantRecord> find(UUID tenantId) {
        List<TenantRecord> rows = jdbc.query(
                """
                SELECT id, display_name, revision, created_at, updated_at
                FROM platform.tenant
                WHERE id = ?
                """,
                (rs, rowNum) -> new TenantRecord(
                        rs.getObject("id", UUID.class),
                        rs.getString("display_name"),
                        rs.getLong("revision"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                tenantId);
        return rows.stream().findFirst();
    }

    @Override
    public void insert(UUID tenantId, String displayName, Instant now) {
        jdbc.update(
                """
                INSERT INTO platform.tenant (
                    id, display_name, revision, created_at, updated_at)
                VALUES (?, ?, 1, ?, ?)
                """,
                tenantId,
                displayName,
                Timestamp.from(now),
                Timestamp.from(now));
    }
}
