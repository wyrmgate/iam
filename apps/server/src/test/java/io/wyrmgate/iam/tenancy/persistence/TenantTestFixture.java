package io.wyrmgate.iam.tenancy.persistence;

import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.tenancy.domain.Tenant;
import io.wyrmgate.iam.tenancy.domain.TenantLifecycleState;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Test-only fixture for inserting already-operational Tenant roots into integration databases. */
public final class TenantTestFixture {

    private final JdbcTemplate jdbc;
    private final IdGenerator ids;

    public TenantTestFixture(JdbcTemplate jdbc, IdGenerator ids) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    public Tenant createActive(String displayName, Instant now) {
        Tenant tenant = new Tenant(
                ids.nextId(),
                displayName,
                TenantLifecycleState.ACTIVE,
                1,
                now,
                now);
        jdbc.update(
                """
                INSERT INTO tenancy.tenant (
                    id, display_name, lifecycle_state, revision, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', 1, ?, ?)
                """,
                tenant.id(),
                tenant.displayName(),
                Timestamp.from(now),
                Timestamp.from(now));
        return tenant;
    }
}
