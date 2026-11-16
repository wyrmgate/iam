package io.wyrmgate.iam.integration.persistence;

import io.wyrmgate.iam.integration.application.IntegrationOperationalStatusRepository;
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

/** Tenant-scoped, read-only persistence adapter for public Integration operational status. */
public final class JdbcIntegrationOperationalStatusRepository
        implements IntegrationOperationalStatusRepository {

    private final JdbcTemplate jdbc;

    public JdbcIntegrationOperationalStatusRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<ProvisioningJobStatus> findProvisioningJob(TenantContext tenant, UUID id) {
        return jdbc.query("""
                SELECT j.id,j.connector_binding_id,j.state,j.revision,j.correlation_id,j.causation_id,
                       j.created_at,j.updated_at, d.task_count,d.blocked_count,
                       CASE WHEN d.task_count=0 THEN j.state
                            WHEN d.blocked_count>0 THEN 'BLOCKED'
                            WHEN d.active_count>0 THEN 'RUNNING'
                            WHEN d.failed_count>0 THEN 'FAILED'
                            WHEN d.superseded_count=d.task_count THEN 'CANCELLED'
                            ELSE 'COMPLETED' END AS fulfillment_state
                FROM integration.provisioning_job j
                CROSS JOIN LATERAL (SELECT count(*) AS task_count,
                    count(*) FILTER(WHERE t.state='BLOCKED') AS blocked_count,
                    count(*) FILTER(WHERE t.state IN ('READY','RUNNING','FAILED_RETRYABLE')) AS active_count,
                    count(*) FILTER(WHERE t.state='FAILED_FINAL') AS failed_count,
                    count(*) FILTER(WHERE t.state='SUPERSEDED') AS superseded_count
                    FROM integration.provisioning_task t
                    WHERE t.tenant_id=j.tenant_id AND t.provisioning_job_id=j.id) d
                WHERE j.tenant_id = ? AND j.id = ?
                """, JdbcIntegrationOperationalStatusRepository::job,
                tenant.tenantId(), id).stream().findFirst();
    }

    @Override
    public List<ProvisioningJobStatus> findProvisioningJobPage(
            TenantContext tenant, PagePosition after, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT j.id,j.connector_binding_id,j.state,j.revision,j.correlation_id,j.causation_id,
                       j.created_at,j.updated_at, d.task_count,d.blocked_count,
                       CASE WHEN d.task_count=0 THEN j.state
                            WHEN d.blocked_count>0 THEN 'BLOCKED'
                            WHEN d.active_count>0 THEN 'RUNNING'
                            WHEN d.failed_count>0 THEN 'FAILED'
                            WHEN d.superseded_count=d.task_count THEN 'CANCELLED'
                            ELSE 'COMPLETED' END AS fulfillment_state
                FROM integration.provisioning_job j
                CROSS JOIN LATERAL (SELECT count(*) AS task_count,
                    count(*) FILTER(WHERE t.state='BLOCKED') AS blocked_count,
                    count(*) FILTER(WHERE t.state IN ('READY','RUNNING','FAILED_RETRYABLE')) AS active_count,
                    count(*) FILTER(WHERE t.state='FAILED_FINAL') AS failed_count,
                    count(*) FILTER(WHERE t.state='SUPERSEDED') AS superseded_count
                    FROM integration.provisioning_task t
                    WHERE t.tenant_id=j.tenant_id AND t.provisioning_job_id=j.id) d
                WHERE j.tenant_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenant.tenantId());
        appendAfter(sql, args, "j.created_at", "j.id", after);
        sql.append(" ORDER BY j.created_at DESC, j.id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), JdbcIntegrationOperationalStatusRepository::job, args.toArray());
    }

    @Override
    public Optional<ProvisioningTaskStatus> findProvisioningTask(TenantContext tenant, UUID id) {
        return jdbc.query("""
                SELECT t.id, t.provisioning_job_id, t.operation_id, t.operation_type, t.subject_kind, t.subject_id,
                       t.desired_revision, t.contract_id, t.contract_version, t.state, t.attempt_count,
                       t.next_attempt_at, t.failure_code, t.revision, t.correlation_id, t.causation_id,
                       t.created_at, t.updated_at, d.id AS recovery_decision_id, d.reconciliation_run_id AS recovery_run_id,
                       d.observation AS recovery_observation, d.disposition AS recovery_disposition,
                       d.corrective_job_id AS recovery_corrective_job_id, d.recorded_at AS recovery_recorded_at
                FROM integration.provisioning_task t
                LEFT JOIN integration.provisioning_recovery_decision d ON d.tenant_id=t.tenant_id AND d.task_id=t.id
                WHERE t.tenant_id = ? AND t.id = ?
                """, JdbcIntegrationOperationalStatusRepository::task,
                tenant.tenantId(), id).stream().findFirst();
    }

    @Override
    public List<ProvisioningTaskStatus> findProvisioningTaskPage(
            TenantContext tenant, PagePosition after, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT t.id, t.provisioning_job_id, t.operation_id, t.operation_type, t.subject_kind, t.subject_id,
                       t.desired_revision, t.contract_id, t.contract_version, t.state, t.attempt_count,
                       t.next_attempt_at, t.failure_code, t.revision, t.correlation_id, t.causation_id,
                       t.created_at, t.updated_at, d.id AS recovery_decision_id, d.reconciliation_run_id AS recovery_run_id,
                       d.observation AS recovery_observation, d.disposition AS recovery_disposition,
                       d.corrective_job_id AS recovery_corrective_job_id, d.recorded_at AS recovery_recorded_at
                FROM integration.provisioning_task t
                LEFT JOIN integration.provisioning_recovery_decision d ON d.tenant_id=t.tenant_id AND d.task_id=t.id
                WHERE t.tenant_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenant.tenantId());
        appendAfter(sql, args, "t.created_at", "t.id", after);
        sql.append(" ORDER BY t.created_at DESC, t.id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), JdbcIntegrationOperationalStatusRepository::task, args.toArray());
    }

    @Override
    public Optional<ProvisioningAttemptStatus> findProvisioningAttempt(TenantContext tenant, UUID id) {
        return jdbc.query("""
                SELECT a.id, a.task_id, a.attempt_number, a.started_at, a.completed_at, a.outcome,
                       a.failure_category, a.correlation_id, a.causation_id,
                       q.state AS effect_recovery_state, q.reason AS effect_recovery_reason,
                       q.updated_at AS effect_recovery_updated_at
                FROM integration.provisioning_attempt a
                LEFT JOIN integration.provisioning_effect_recovery q
                  ON q.tenant_id = a.tenant_id AND q.attempt_id = a.id
                WHERE a.tenant_id = ? AND a.id = ?
                """, JdbcIntegrationOperationalStatusRepository::attempt,
                tenant.tenantId(), id).stream().findFirst();
    }

    @Override
    public List<ProvisioningAttemptStatus> findProvisioningAttemptPage(
            TenantContext tenant, UUID taskId, PagePosition after, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT a.id, a.task_id, a.attempt_number, a.started_at, a.completed_at, a.outcome,
                       a.failure_category, a.correlation_id, a.causation_id,
                       q.state AS effect_recovery_state, q.reason AS effect_recovery_reason,
                       q.updated_at AS effect_recovery_updated_at
                FROM integration.provisioning_attempt a
                LEFT JOIN integration.provisioning_effect_recovery q
                  ON q.tenant_id = a.tenant_id AND q.attempt_id = a.id
                WHERE a.tenant_id = ? AND a.task_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenant.tenantId());
        args.add(taskId);
        appendAfter(sql, args, "completed_at", after);
        sql.append(" ORDER BY completed_at DESC, id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), JdbcIntegrationOperationalStatusRepository::attempt, args.toArray());
    }

    @Override
    public Optional<ReconciliationRunStatus> findReconciliationRun(TenantContext tenant, UUID id) {
        return jdbc.query("""
                SELECT id, connector_binding_id, operation_id, scope_object_class, scope_mode, state,
                       reported_coverage, effective_completeness, configuration_version,
                       runtime_id, runtime_version, contract_id, contract_version, next_attempt_at,
                       failure_code, correlation_id, causation_id, revision, started_at, completed_at,
                       created_at, updated_at
                FROM integration.reconciliation_run
                WHERE tenant_id = ? AND id = ?
                """, JdbcIntegrationOperationalStatusRepository::run,
                tenant.tenantId(), id).stream().findFirst();
    }

    @Override
    public List<ReconciliationRunStatus> findReconciliationRunPage(
            TenantContext tenant, PagePosition after, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, connector_binding_id, operation_id, scope_object_class, scope_mode, state,
                       reported_coverage, effective_completeness, configuration_version,
                       runtime_id, runtime_version, contract_id, contract_version, next_attempt_at,
                       failure_code, correlation_id, causation_id, revision, started_at, completed_at,
                       created_at, updated_at
                FROM integration.reconciliation_run
                WHERE tenant_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenant.tenantId());
        appendAfter(sql, args, "created_at", after);
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), JdbcIntegrationOperationalStatusRepository::run, args.toArray());
    }

    private static void appendAfter(
            StringBuilder sql, List<Object> args, String timestampColumn, PagePosition after) {
        appendAfter(sql,args,timestampColumn,"id",after);
    }

    private static void appendAfter(StringBuilder sql,List<Object> args,String timestampColumn,String idColumn,PagePosition after) {
        if (after == null) return;
        sql.append(" AND (").append(timestampColumn).append(" < ? OR (")
                .append(timestampColumn).append(" = ? AND ").append(idColumn).append(" < ?))");
        Timestamp timestamp = Timestamp.from(after.sortAt());
        args.add(timestamp);
        args.add(timestamp);
        args.add(after.id());
    }

    private static ProvisioningJobStatus job(ResultSet rs, int rowNum) throws SQLException {
        return new ProvisioningJobStatus(
                rs.getObject("id", UUID.class),
                rs.getObject("connector_binding_id", UUID.class),
                rs.getString("state"),
                rs.getString("fulfillment_state"),rs.getLong("task_count"),rs.getLong("blocked_count"),
                rs.getLong("revision"),
                rs.getObject("correlation_id", UUID.class),
                rs.getObject("causation_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static ProvisioningTaskStatus task(ResultSet rs, int rowNum) throws SQLException {
        return new ProvisioningTaskStatus(
                rs.getObject("id", UUID.class),
                rs.getObject("provisioning_job_id", UUID.class),
                rs.getObject("operation_id", UUID.class),
                rs.getString("operation_type"),
                rs.getString("subject_kind"),
                rs.getObject("subject_id", UUID.class),
                rs.getLong("desired_revision"),
                rs.getString("contract_id"),
                rs.getInt("contract_version"),
                rs.getString("state"),
                rs.getInt("attempt_count"),
                instant(rs, "next_attempt_at"),
                rs.getString("failure_code"),
                rs.getObject("recovery_decision_id", UUID.class),
                rs.getObject("recovery_run_id", UUID.class),
                rs.getString("recovery_observation"),
                rs.getString("recovery_disposition"),
                rs.getObject("recovery_corrective_job_id", UUID.class),
                instant(rs, "recovery_recorded_at"),
                rs.getLong("revision"),
                rs.getObject("correlation_id", UUID.class),
                rs.getObject("causation_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static ProvisioningAttemptStatus attempt(ResultSet rs, int rowNum) throws SQLException {
        return new ProvisioningAttemptStatus(
                rs.getObject("id", UUID.class),
                rs.getObject("task_id", UUID.class),
                rs.getInt("attempt_number"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("completed_at").toInstant(),
                rs.getString("outcome"),
                rs.getString("failure_category"),
                rs.getString("effect_recovery_state"),
                rs.getString("effect_recovery_reason"),
                instant(rs, "effect_recovery_updated_at"),
                rs.getObject("correlation_id", UUID.class),
                rs.getObject("causation_id", UUID.class));
    }

    private static ReconciliationRunStatus run(ResultSet rs, int rowNum) throws SQLException {
        return new ReconciliationRunStatus(
                rs.getObject("id", UUID.class),
                rs.getObject("connector_binding_id", UUID.class),
                rs.getObject("operation_id", UUID.class),
                rs.getString("scope_object_class"),
                rs.getString("scope_mode"),
                rs.getString("state"),
                rs.getString("reported_coverage"),
                rs.getString("effective_completeness"),
                rs.getLong("configuration_version"),
                rs.getString("runtime_id"),
                rs.getString("runtime_version"),
                rs.getString("contract_id"),
                rs.getInt("contract_version"),
                instant(rs, "next_attempt_at"),
                rs.getString("failure_code"),
                rs.getObject("correlation_id", UUID.class),
                rs.getObject("causation_id", UUID.class),
                rs.getLong("revision"),
                rs.getTimestamp("started_at").toInstant(),
                instant(rs, "completed_at"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static java.time.Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
