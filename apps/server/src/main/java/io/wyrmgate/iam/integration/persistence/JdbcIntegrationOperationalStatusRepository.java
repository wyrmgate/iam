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
                SELECT id, connector_binding_id, state, revision, correlation_id, causation_id,
                       created_at, updated_at
                FROM integration.provisioning_job
                WHERE tenant_id = ? AND id = ?
                """, JdbcIntegrationOperationalStatusRepository::job,
                tenant.tenantId(), id).stream().findFirst();
    }

    @Override
    public List<ProvisioningJobStatus> findProvisioningJobPage(
            TenantContext tenant, PagePosition after, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, connector_binding_id, state, revision, correlation_id, causation_id,
                       created_at, updated_at
                FROM integration.provisioning_job
                WHERE tenant_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenant.tenantId());
        appendAfter(sql, args, "created_at", after);
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), JdbcIntegrationOperationalStatusRepository::job, args.toArray());
    }

    @Override
    public Optional<ProvisioningTaskStatus> findProvisioningTask(TenantContext tenant, UUID id) {
        return jdbc.query("""
                SELECT id, provisioning_job_id, operation_id, operation_type, subject_kind, subject_id,
                       desired_revision, contract_id, contract_version, state, attempt_count,
                       next_attempt_at, failure_code, revision, correlation_id, causation_id,
                       created_at, updated_at
                FROM integration.provisioning_task
                WHERE tenant_id = ? AND id = ?
                """, JdbcIntegrationOperationalStatusRepository::task,
                tenant.tenantId(), id).stream().findFirst();
    }

    @Override
    public List<ProvisioningTaskStatus> findProvisioningTaskPage(
            TenantContext tenant, PagePosition after, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, provisioning_job_id, operation_id, operation_type, subject_kind, subject_id,
                       desired_revision, contract_id, contract_version, state, attempt_count,
                       next_attempt_at, failure_code, revision, correlation_id, causation_id,
                       created_at, updated_at
                FROM integration.provisioning_task
                WHERE tenant_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenant.tenantId());
        appendAfter(sql, args, "created_at", after);
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), JdbcIntegrationOperationalStatusRepository::task, args.toArray());
    }

    @Override
    public Optional<ProvisioningAttemptStatus> findProvisioningAttempt(TenantContext tenant, UUID id) {
        return jdbc.query("""
                SELECT id, task_id, attempt_number, started_at, completed_at, outcome,
                       failure_category, correlation_id, causation_id
                FROM integration.provisioning_attempt
                WHERE tenant_id = ? AND id = ?
                """, JdbcIntegrationOperationalStatusRepository::attempt,
                tenant.tenantId(), id).stream().findFirst();
    }

    @Override
    public List<ProvisioningAttemptStatus> findProvisioningAttemptPage(
            TenantContext tenant, UUID taskId, PagePosition after, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, task_id, attempt_number, started_at, completed_at, outcome,
                       failure_category, correlation_id, causation_id
                FROM integration.provisioning_attempt
                WHERE tenant_id = ? AND task_id = ?
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
                SELECT id, connector_binding_id, operation_id, scope_object_class, state,
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
                SELECT id, connector_binding_id, operation_id, scope_object_class, state,
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
        if (after == null) return;
        sql.append(" AND (").append(timestampColumn).append(" < ? OR (")
                .append(timestampColumn).append(" = ? AND id < ?))");
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
                rs.getObject("correlation_id", UUID.class),
                rs.getObject("causation_id", UUID.class));
    }

    private static ReconciliationRunStatus run(ResultSet rs, int rowNum) throws SQLException {
        return new ReconciliationRunStatus(
                rs.getObject("id", UUID.class),
                rs.getObject("connector_binding_id", UUID.class),
                rs.getObject("operation_id", UUID.class),
                rs.getString("scope_object_class"),
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
