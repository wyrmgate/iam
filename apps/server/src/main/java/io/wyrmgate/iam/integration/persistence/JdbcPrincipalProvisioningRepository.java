package io.wyrmgate.iam.integration.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.integration.application.PrincipalProvisioningRepository;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcPrincipalProvisioningRepository
        implements PrincipalProvisioningRepository {

    private static final String SCIM_PRINCIPAL_CONTRACT = "scim-2.principal";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final IdGenerator ids;

    public JdbcPrincipalProvisioningRepository(
            JdbcTemplate jdbc,
            ObjectMapper json,
            IdGenerator ids) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.json = Objects.requireNonNull(json, "json");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    public List<TechnicalPrincipalTarget> creationTargets(
            TenantContext tenant,
            UUID applicationTargetId) {
        return jdbc.query("""
                SELECT b.id, b.contract_id, b.contract_version,
                       ci.configuration_json ->> 'principalUserNameTemplate'
                FROM integration.connector_binding b
                JOIN integration.connector_instance ci
                  ON ci.tenant_id = b.tenant_id
                 AND ci.id = b.connector_instance_id
                 AND ci.lifecycle_state = 'ACTIVE'
                WHERE b.tenant_id = ?
                  AND b.target_kind = 'APPLICATION_TARGET'
                  AND b.target_id = ?
                  AND b.lifecycle_state = 'ACTIVE'
                  AND b.contract_id = ?
                ORDER BY b.id
                """,
                (rs,row) -> new TechnicalPrincipalTarget(
                        rs.getObject(1, UUID.class),
                        rs.getString(2),
                        rs.getInt(3),
                        null,
                        null,
                        rs.getString(4)),
                tenant.tenantId(),
                applicationTargetId,
                SCIM_PRINCIPAL_CONTRACT);
    }

    @Override
    public List<TechnicalPrincipalTarget> existingTargets(
            TenantContext tenant,
            UUID applicationTargetId,
            UUID desiredPrincipalId,
            String providerPrincipalId) {
        LinkedHashMap<String,TechnicalPrincipalTarget> targets =
                new LinkedHashMap<>();

        for (var target : jdbc.query("""
                SELECT b.id, b.contract_id, b.contract_version,
                       p.provider_stable_id, p.provider_version,
                       ci.configuration_json ->> 'principalUserNameTemplate'
                FROM integration.observed_principal p
                JOIN integration.connector_binding b
                  ON b.tenant_id = p.tenant_id
                 AND b.id = p.connector_binding_id
                 AND b.target_kind = 'APPLICATION_TARGET'
                 AND b.target_id = ?
                 AND b.lifecycle_state = 'ACTIVE'
                 AND b.contract_id = ?
                JOIN integration.connector_instance ci
                  ON ci.tenant_id = b.tenant_id
                 AND ci.id = b.connector_instance_id
                 AND ci.lifecycle_state = 'ACTIVE'
                WHERE p.tenant_id = ?
                  AND p.provider_stable_id = ?
                  AND p.present
                ORDER BY b.id
                """,
                (rs,row) -> new TechnicalPrincipalTarget(
                        rs.getObject(1, UUID.class),
                        rs.getString(2),
                        rs.getInt(3),
                        rs.getString(4),
                        rs.getString(5),
                        rs.getString(6)),
                applicationTargetId,
                SCIM_PRINCIPAL_CONTRACT,
                tenant.tenantId(),
                providerPrincipalId)) {
            targets.put(target.connectorBindingId() + "|" + target.providerPrincipalId(), target);
        }

        for (var target : jdbc.query("""
                SELECT j.connector_binding_id,
                       t.contract_id,
                       t.contract_version,
                       a.provider_object_id,
                       a.provider_version,
                       ci.configuration_json ->> 'principalUserNameTemplate'
                FROM integration.provisioning_task t
                JOIN integration.provisioning_job j
                  ON j.tenant_id = t.tenant_id
                 AND j.id = t.provisioning_job_id
                JOIN integration.connector_binding b
                  ON b.tenant_id = j.tenant_id
                 AND b.id = j.connector_binding_id
                 AND b.lifecycle_state = 'ACTIVE'
                 AND b.target_kind = 'APPLICATION_TARGET'
                 AND b.target_id = ?
                 AND b.contract_id = ?
                JOIN integration.connector_instance ci
                  ON ci.tenant_id = b.tenant_id
                 AND ci.id = b.connector_instance_id
                 AND ci.lifecycle_state = 'ACTIVE'
                JOIN integration.provisioning_attempt a
                  ON a.tenant_id = t.tenant_id
                 AND a.task_id = t.id
                 AND a.outcome = 'SUCCEEDED'
                 AND a.provider_object_id IS NOT NULL
                WHERE t.tenant_id = ?
                  AND t.subject_kind = 'DESIRED_PRINCIPAL'
                  AND t.subject_id = ?
                  AND t.operation_type = 'UPSERT_PRINCIPAL'
                  AND a.provider_object_id = ?
                ORDER BY a.completed_at DESC, t.id
                """,
                (rs,row) -> new TechnicalPrincipalTarget(
                        rs.getObject(1, UUID.class),
                        rs.getString(2),
                        rs.getInt(3),
                        rs.getString(4),
                        rs.getString(5),
                        rs.getString(6)),
                applicationTargetId,
                SCIM_PRINCIPAL_CONTRACT,
                tenant.tenantId(),
                desiredPrincipalId,
                providerPrincipalId)) {
            targets.put(target.connectorBindingId() + "|" + target.providerPrincipalId(), target);
        }

        return List.copyOf(targets.values());
    }

    @Override
    public boolean createPlan(
            TenantContext tenant,
            String planKey,
            UUID desiredPrincipalId,
            long desiredRevision,
            List<TaskSpec> tasks,
            UUID correlationId,
            UUID causationId,
            Instant now) {
        if (tasks.isEmpty()) return false;
        Map<UUID,List<TaskSpec>> byBinding = new LinkedHashMap<>();
        for (TaskSpec task : tasks) {
            byBinding.computeIfAbsent(
                    task.connectorBindingId(), ignored -> new ArrayList<>())
                    .add(task);
        }

        boolean createdAny = false;
        for (var entry : byBinding.entrySet()) {
            UUID bindingId = entry.getKey();
            String bindingPlanKey = planKey + ":" + bindingId;
            UUID jobId = ids.nextId();
            int inserted = jdbc.update("""
                    INSERT INTO integration.provisioning_job (
                        id, tenant_id, connector_binding_id, plan_key,
                        state, revision, correlation_id, causation_id,
                        created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'RUNNING', 1, ?, ?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """,
                    jobId,
                    tenant.tenantId(),
                    bindingId,
                    bindingPlanKey,
                    correlationId,
                    causationId,
                    Timestamp.from(now),
                    Timestamp.from(now));
            if (inserted == 0) continue;
            createdAny = true;

            for (TaskSpec task : entry.getValue()) {
                jdbc.update("""
                        INSERT INTO integration.provisioning_task (
                            id, tenant_id, provisioning_job_id,
                            operation_id, operation_type,
                            subject_kind, subject_id, desired_revision,
                            idempotency_key, contract_id, contract_version,
                            payload, state, attempt_count, next_attempt_at,
                            failure_code, revision,
                            correlation_id, causation_id,
                            created_at, updated_at)
                        VALUES (
                            ?, ?, ?, ?, ?,
                            'DESIRED_PRINCIPAL', ?, ?,
                            ?, ?, ?,
                            ?::jsonb, 'READY', 0, NULL,
                            NULL, 1,
                            ?, ?, ?, ?)
                        """,
                        ids.nextId(),
                        tenant.tenantId(),
                        jobId,
                        ids.nextId(),
                        task.operationType(),
                        desiredPrincipalId,
                        desiredRevision,
                        task.taskKey(),
                        task.contractId(),
                        task.contractVersion(),
                        writeJson(task.payload()),
                        correlationId,
                        causationId,
                        Timestamp.from(now),
                        Timestamp.from(now));
            }
        }
        return createdAny;
    }

    private String writeJson(Map<String,Object> value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException(
                    "principal provisioning payload is not JSON serializable", invalid);
        }
    }
}
