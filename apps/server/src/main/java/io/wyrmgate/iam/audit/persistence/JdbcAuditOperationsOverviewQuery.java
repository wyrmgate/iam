package io.wyrmgate.iam.audit.persistence;

import io.wyrmgate.iam.audit.application.AuditOperationsOverviewQuery;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuditOperationsOverviewQuery implements AuditOperationsOverviewQuery {
    private final JdbcTemplate jdbc;

    public JdbcAuditOperationsOverviewQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Evidence evidence(TenantContext tenant, Instant from, Instant until, int recentLimit) {
        if (recentLimit < 1 || recentLimit > 5) {
            throw new IllegalArgumentException("overview audit limit must be between 1 and 5");
        }
        Map<String,Object> totals = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE outcome = 'DENIED') AS denied,
                       count(*) FILTER (WHERE outcome = 'FAILURE') AS failure
                FROM audit.audit_record
                WHERE tenant_id = ? AND occurred_at >= ? AND occurred_at < ?
                """, tenant.tenantId(), Timestamp.from(from), Timestamp.from(until));
        List<EvidenceItem> recent = jdbc.query("""
                SELECT id, occurred_at, action_type, resource_type, resource_id, outcome, correlation_id
                FROM audit.audit_record
                WHERE tenant_id = ? AND occurred_at >= ? AND occurred_at < ? AND outcome IN ('DENIED', 'FAILURE')
                ORDER BY occurred_at DESC, id DESC LIMIT ?
                """, (rs,row) -> new EvidenceItem(rs.getObject("id", UUID.class), rs.getTimestamp("occurred_at").toInstant(),
                        rs.getString("action_type"), rs.getString("resource_type"), rs.getObject("resource_id", UUID.class),
                        rs.getString("outcome"), rs.getObject("correlation_id", UUID.class)),
                tenant.tenantId(), Timestamp.from(from), Timestamp.from(until), recentLimit);
        return new Evidence(number(totals.get("denied")), number(totals.get("failure")), recent);
    }

    @Override
    public List<ActivityItem> recentActivity(TenantContext tenant, Instant from, Instant until, int recentLimit) {
        if (recentLimit < 1 || recentLimit > 10) {
            throw new IllegalArgumentException("overview activity limit must be between 1 and 10");
        }
        return jdbc.query("""
                SELECT id, occurred_at, actor_id,
                       material_snapshot ->> 'actorDisplayLabel' AS actor_display_label,
                       action_type, resource_type, resource_id,
                       material_snapshot ->> 'resourceDisplayLabel' AS resource_display_label,
                       outcome
                FROM audit.audit_record_query
                WHERE tenant_id = ? AND occurred_at >= ? AND occurred_at < ?
                ORDER BY occurred_at DESC, id DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new ActivityItem(
                        rs.getObject("id", UUID.class),
                        rs.getTimestamp("occurred_at").toInstant(),
                        rs.getObject("actor_id", UUID.class),
                        rs.getString("actor_display_label"),
                        rs.getString("action_type"),
                        rs.getString("resource_type"),
                        rs.getObject("resource_id", UUID.class),
                        rs.getString("resource_display_label"),
                        rs.getString("outcome")),
                tenant.tenantId(), Timestamp.from(from), Timestamp.from(until), recentLimit);
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
