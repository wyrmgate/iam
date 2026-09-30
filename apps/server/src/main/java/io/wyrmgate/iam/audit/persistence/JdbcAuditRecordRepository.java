package io.wyrmgate.iam.audit.persistence;

import io.wyrmgate.iam.audit.application.AuditQueryModels.AuditFilter;
import io.wyrmgate.iam.audit.application.AuditQueryModels.AuditPagePosition;
import io.wyrmgate.iam.audit.application.AuditRecordRepository;
import io.wyrmgate.iam.audit.domain.AuditOutcome;
import io.wyrmgate.iam.audit.domain.AuditRecord;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcAuditRecordRepository implements AuditRecordRepository {
    private final JdbcTemplate jdbc;

    public JdbcAuditRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public boolean insertIfAbsent(TenantContext tenant, AuditRecord record) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(record, "record");
        return jdbc.update(
                """
                INSERT INTO audit.audit_record (
                    id, tenant_id, occurred_at, recorded_at, actor_id,
                    action_type, resource_type, resource_id, outcome,
                    correlation_id, causation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """,
                record.id(),
                tenant.tenantId(),
                Timestamp.from(record.occurredAt()),
                Timestamp.from(record.recordedAt()),
                record.actorId(),
                record.actionType(),
                record.resourceType(),
                record.resourceId(),
                record.outcome().name(),
                record.correlationId(),
                record.causationId()) == 1;
    }

    @Override
    public Optional<AuditRecord> findById(TenantContext tenant, UUID recordId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(recordId, "recordId");
        List<AuditRecord> rows = jdbc.query(
                """
                SELECT id, occurred_at, recorded_at, actor_id, action_type,
                       resource_type, resource_id, outcome, correlation_id, causation_id
                FROM audit.audit_record
                WHERE tenant_id = ? AND id = ?
                """,
                (rs, rowNum) -> row(rs),
                tenant.tenantId(),
                recordId);
        return rows.stream().findFirst();
    }

    @Override
    public List<AuditRecord> findPage(
            TenantContext tenant,
            AuditFilter filter,
            AuditPagePosition after,
            int limit) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(filter, "filter");
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");

        StringBuilder sql = new StringBuilder("""
                SELECT id, occurred_at, recorded_at, actor_id, action_type,
                       resource_type, resource_id, outcome, correlation_id, causation_id
                FROM audit.audit_record
                WHERE tenant_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenant.tenantId());

        if (filter.actorId() != null) {
            sql.append(" AND actor_id = ?");
            args.add(filter.actorId());
        }
        if (filter.actionType() != null) {
            sql.append(" AND action_type = ?");
            args.add(filter.actionType());
        }
        if (filter.resourceType() != null) {
            sql.append(" AND resource_type = ?");
            args.add(filter.resourceType());
        }
        if (filter.resourceId() != null) {
            sql.append(" AND resource_id = ?");
            args.add(filter.resourceId());
        }
        if (filter.outcome() != null) {
            sql.append(" AND outcome = ?");
            args.add(filter.outcome().name());
        }
        if (filter.correlationId() != null) {
            sql.append(" AND correlation_id = ?");
            args.add(filter.correlationId());
        }
        if (after != null) {
            sql.append(" AND (occurred_at < ? OR (occurred_at = ? AND id < ?))");
            args.add(Timestamp.from(after.occurredAt()));
            args.add(Timestamp.from(after.occurredAt()));
            args.add(after.id());
        }
        sql.append(" ORDER BY occurred_at DESC, id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, rowNum) -> row(rs), args.toArray());
    }

    private static AuditRecord row(ResultSet rs) throws SQLException {
        return new AuditRecord(
                rs.getObject("id", UUID.class),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getTimestamp("recorded_at").toInstant(),
                rs.getObject("actor_id", UUID.class),
                rs.getString("action_type"),
                rs.getString("resource_type"),
                rs.getObject("resource_id", UUID.class),
                AuditOutcome.valueOf(rs.getString("outcome")),
                rs.getObject("correlation_id", UUID.class),
                rs.getObject("causation_id", UUID.class));
    }
}
