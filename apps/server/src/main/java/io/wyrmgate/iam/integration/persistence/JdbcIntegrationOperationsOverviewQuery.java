package io.wyrmgate.iam.integration.persistence;

import io.wyrmgate.iam.integration.application.IntegrationOperationsOverviewQuery;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public final class JdbcIntegrationOperationsOverviewQuery implements IntegrationOperationsOverviewQuery {
    private final JdbcTemplate jdbc;
    public JdbcIntegrationOperationsOverviewQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public long finalProvisioningFailures(TenantContext tenant, Instant from, Instant until) {
        Long value = jdbc.queryForObject("""
                SELECT count(*) FROM integration.provisioning_attempt
                WHERE tenant_id = ? AND outcome = 'FAILED_FINAL' AND completed_at >= ? AND completed_at < ?
                """, Long.class, tenant.tenantId(), Timestamp.from(from), Timestamp.from(until));
        return value == null ? 0 : value;
    }

    @Override
    public Provisioning provisioning(TenantContext tenant, Instant from, Instant until, BucketUnit bucketUnit, int bucketCount) {
        if (bucketCount < 1 || bucketCount > 30) throw new IllegalArgumentException("overview bucket count must be between 1 and 30");
        Long running = jdbc.queryForObject("SELECT count(*) FROM integration.provisioning_task WHERE tenant_id = ? AND state = 'RUNNING'",
                Long.class, tenant.tenantId());
        Map<String,Object> totals = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE outcome = 'SUCCEEDED') AS succeeded,
                       count(*) FILTER (WHERE outcome = 'FAILED_RETRYABLE') AS retryable,
                       count(*) FILTER (WHERE outcome = 'FAILED_FINAL') AS final_failed
                FROM integration.provisioning_attempt
                WHERE tenant_id = ? AND completed_at >= ? AND completed_at < ?
                """, tenant.tenantId(), Timestamp.from(from), Timestamp.from(until));
        String trunc = bucketUnit == BucketUnit.HOUR ? "hour" : "day";
        List<Map<String,Object>> rows = jdbc.queryForList("""
                SELECT (date_trunc(?, completed_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC') AS bucket_start,
                       count(*) FILTER (WHERE outcome = 'SUCCEEDED') AS succeeded,
                       count(*) FILTER (WHERE outcome = 'FAILED_RETRYABLE') AS retryable,
                       count(*) FILTER (WHERE outcome = 'FAILED_FINAL') AS final_failed
                FROM integration.provisioning_attempt
                WHERE tenant_id = ? AND completed_at >= ? AND completed_at < ?
                GROUP BY 1 ORDER BY 1
                """, trunc, tenant.tenantId(), Timestamp.from(from), Timestamp.from(until));
        Map<Instant,AttemptBucket> byStart = new LinkedHashMap<>();
        for (Map<String,Object> row : rows) {
            Instant start = ((Timestamp) row.get("bucket_start")).toInstant();
            byStart.put(start, new AttemptBucket(start, number(row.get("succeeded")), number(row.get("retryable")), number(row.get("final_failed"))));
        }
        Duration step = bucketUnit == BucketUnit.HOUR ? Duration.ofHours(1) : Duration.ofDays(1);
        List<AttemptBucket> trend = new ArrayList<>(bucketCount);
        Instant cursor = from;
        for (int index = 0; index < bucketCount; index++) {
            AttemptBucket bucket = byStart.get(cursor);
            trend.add(bucket == null ? new AttemptBucket(cursor, 0, 0, 0) : bucket);
            cursor = cursor.plus(step);
        }
        List<FailureCategory> categories = jdbc.query("""
                SELECT COALESCE(failure_category, 'UNCATEGORIZED') AS category, count(*) AS total
                FROM integration.provisioning_attempt
                WHERE tenant_id = ? AND completed_at >= ? AND completed_at < ?
                  AND outcome IN ('FAILED_RETRYABLE', 'FAILED_FINAL')
                GROUP BY COALESCE(failure_category, 'UNCATEGORIZED')
                ORDER BY total DESC, category LIMIT 8
                """, (rs,row) -> new FailureCategory(rs.getString("category"), rs.getLong("total")),
                tenant.tenantId(), Timestamp.from(from), Timestamp.from(until));
        return new Provisioning(running == null ? 0 : running, number(totals.get("succeeded")), number(totals.get("retryable")),
                number(totals.get("final_failed")), trend, categories);
    }

    @Override
    public Reconciliation reconciliation(TenantContext tenant, Instant from, Instant until, int recentLimit) {
        if (recentLimit < 1 || recentLimit > 6) throw new IllegalArgumentException("overview reconciliation limit must be between 1 and 6");
        Long running = jdbc.queryForObject("SELECT count(*) FROM integration.reconciliation_run WHERE tenant_id = ? AND state = 'RUNNING'",
                Long.class, tenant.tenantId());
        Map<String,Object> totals = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE state = 'FAILED') AS failed,
                       count(*) FILTER (WHERE state = 'SUCCEEDED' AND effective_completeness = 'COMPLETE') AS complete_succeeded
                FROM integration.reconciliation_run
                WHERE tenant_id = ? AND completed_at >= ? AND completed_at < ?
                """, tenant.tenantId(), Timestamp.from(from), Timestamp.from(until));
        var lastComplete = jdbc.query("""
                SELECT id, completed_at FROM integration.reconciliation_run
                WHERE tenant_id = ? AND state = 'SUCCEEDED' AND effective_completeness = 'COMPLETE'
                ORDER BY completed_at DESC, id DESC LIMIT 1
                """, (rs,row) -> Map.entry(rs.getObject("id", UUID.class), rs.getTimestamp("completed_at").toInstant()),
                tenant.tenantId()).stream().findFirst();
        List<ReconciliationRun> recent = jdbc.query("""
                SELECT id, connector_binding_id, state, effective_completeness, failure_code, started_at, completed_at
                FROM integration.reconciliation_run
                WHERE tenant_id = ? AND (state = 'RUNNING' OR (completed_at >= ? AND completed_at < ?))
                ORDER BY COALESCE(completed_at, started_at) DESC, id DESC LIMIT ?
                """, (rs,row) -> {
                    Timestamp completed = rs.getTimestamp("completed_at");
                    return new ReconciliationRun(rs.getObject("id", UUID.class), rs.getObject("connector_binding_id", UUID.class),
                            rs.getString("state"), rs.getString("effective_completeness"), rs.getString("failure_code"),
                            rs.getTimestamp("started_at").toInstant(), completed == null ? null : completed.toInstant());
                }, tenant.tenantId(), Timestamp.from(from), Timestamp.from(until), recentLimit);
        return new Reconciliation(running == null ? 0 : running, number(totals.get("failed")), number(totals.get("complete_succeeded")),
                lastComplete.map(Map.Entry::getValue).orElse(null), lastComplete.map(Map.Entry::getKey).orElse(null), recent);
    }

    @Override
    public long activeConnectors(TenantContext tenant) {
        Long value = jdbc.queryForObject("SELECT count(*) FROM integration.connector_instance WHERE tenant_id = ? AND lifecycle_state = 'ACTIVE'",
                Long.class, tenant.tenantId());
        return value == null ? 0 : value;
    }

    private static long number(Object value) { return value instanceof Number number ? number.longValue() : 0L; }
}
