package io.wyrmgate.iam.platform.persistence;

import io.wyrmgate.iam.platform.id.IdGenerator;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC adapter for the Platform-owned tenant isolation root. */
public final class JdbcTenantRepository {

    private final JdbcTemplate jdbcTemplate;
    private final IdGenerator idGenerator;

    public JdbcTenantRepository(JdbcTemplate jdbcTemplate, IdGenerator idGenerator) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    }

    public Tenant create(String displayName, Instant now) {
        requireText(displayName, "displayName");
        Objects.requireNonNull(now, "now");
        UUID id = idGenerator.nextId();
        jdbcTemplate.update(
                """
                INSERT INTO platform.tenant (id, display_name, revision, created_at, updated_at)
                VALUES (?, ?, 1, ?, ?)
                """,
                id,
                displayName,
                now,
                now);
        return new Tenant(id, displayName, 1, now, now);
    }

    public Tenant get(UUID id) {
        Objects.requireNonNull(id, "id");
        return jdbcTemplate.queryForObject(
                """
                SELECT id, display_name, revision, created_at, updated_at
                FROM platform.tenant
                WHERE id = ?
                """,
                (rs, rowNum) -> new Tenant(
                        rs.getObject("id", UUID.class),
                        rs.getString("display_name"),
                        rs.getLong("revision"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                id);
    }

    public Tenant rename(UUID id, String displayName, long expectedRevision, Instant now) {
        Objects.requireNonNull(id, "id");
        requireText(displayName, "displayName");
        Objects.requireNonNull(now, "now");
        if (expectedRevision < 1) {
            throw new IllegalArgumentException("expectedRevision must be positive");
        }

        int affected = jdbcTemplate.update(
                """
                UPDATE platform.tenant
                SET display_name = ?, revision = revision + 1, updated_at = ?
                WHERE id = ? AND revision = ?
                """,
                displayName,
                now,
                id,
                expectedRevision);
        OptimisticUpdate.requireSingleRow(affected, "tenant", id, expectedRevision);
        return get(id);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    public record Tenant(
            UUID id,
            String displayName,
            long revision,
            Instant createdAt,
            Instant updatedAt) {
    }
}
