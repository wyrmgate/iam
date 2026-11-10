package io.wyrmgate.iam.tenancy.persistence;

import io.wyrmgate.iam.platform.persistence.OptimisticUpdate;
import io.wyrmgate.iam.tenancy.application.TenantRepository;
import io.wyrmgate.iam.tenancy.domain.Tenant;
import io.wyrmgate.iam.tenancy.domain.TenantLifecycleState;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC adapter for Tenancy-owned authoritative Tenant state. */
public final class JdbcTenantRepository implements TenantRepository {

    private final JdbcTemplate jdbc;

    public JdbcTenantRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public long count() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM tenancy.tenant", Long.class);
        return count == null ? 0L : count;
    }

    @Override
    public Optional<Tenant> find(UUID tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        List<Tenant> rows = jdbc.query(
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
    public void insert(Tenant tenant) {
        Objects.requireNonNull(tenant, "tenant");
        jdbc.update(
                """
                INSERT INTO tenancy.tenant (
                    id, display_name, lifecycle_state, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                tenant.id(),
                tenant.displayName(),
                tenant.lifecycleState().name(),
                tenant.revision(),
                Timestamp.from(tenant.createdAt()),
                Timestamp.from(tenant.updatedAt()));
    }

    @Override
    public Tenant updateLifecycle(Tenant tenant, long expectedRevision) {
        Objects.requireNonNull(tenant, "tenant");
        int affected = jdbc.update(
                """
                UPDATE tenancy.tenant
                SET lifecycle_state = ?, revision = ?, updated_at = ?
                WHERE id = ? AND revision = ?
                """,
                tenant.lifecycleState().name(),
                tenant.revision(),
                Timestamp.from(tenant.updatedAt()),
                tenant.id(),
                expectedRevision);
        OptimisticUpdate.requireSingleRow(
                affected, "tenant", tenant.id(), expectedRevision);
        return find(tenant.id()).orElseThrow();
    }

    private static Tenant map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Tenant(
                rs.getObject("id", UUID.class),
                rs.getString("display_name"),
                TenantLifecycleState.valueOf(rs.getString("lifecycle_state")),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
