package io.wyrmgate.iam.integration.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.integration.application.GrantProvisioningRepository;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcGrantProvisioningRepository
        implements GrantProvisioningRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final IdGenerator ids;

    public JdbcGrantProvisioningRepository(
            JdbcTemplate jdbc,
            ObjectMapper json,
            IdGenerator ids) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.json = Objects.requireNonNull(json, "json");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    public List<TechnicalGrantTarget> addTargets(
            TenantContext tenant,
            UUID applicationTargetId,
            UUID entitlementId,
            String providerPrincipalId) {
        return jdbc.query("""
                SELECT b.id, b.contract_id, b.contract_version,
                       e.provider_stable_id, e.provider_version
                FROM integration.connector_binding b
                JOIN integration.connector_instance ci
                  ON ci.tenant_id = b.tenant_id
                 AND ci.id = b.connector_instance_id
                 AND ci.lifecycle_state = 'ACTIVE'
                JOIN integration.entitlement_observation_mapping m
                  ON m.tenant_id = b.tenant_id
                 AND m.connector_binding_id = b.id
                 AND m.entitlement_id = ?
                 AND m.lifecycle_state = 'ACTIVE'
                JOIN integration.observed_entitlement e
                  ON e.tenant_id = m.tenant_id
                 AND e.connector_binding_id = m.connector_binding_id
                 AND e.provider_stable_id = m.provider_stable_id
                 AND e.present
                WHERE b.tenant_id = ?
                  AND b.target_kind = 'APPLICATION_TARGET'
                  AND b.target_id = ?
                  AND b.lifecycle_state = 'ACTIVE'
                ORDER BY b.id, e.provider_stable_id
                LIMIT 2
                """,
                (rs,row) -> new TechnicalGrantTarget(
                        rs.getObject(1, UUID.class),
                        rs.getString(2),
                        rs.getInt(3),
                        rs.getString(4),
                        rs.getString(5),
                        providerPrincipalId,
                        null),
                entitlementId,
                tenant.tenantId(),
                applicationTargetId);
    }

    @Override
    public boolean hasUninterpretablePriorEvidence(
            TenantContext tenant, UUID desiredGrantId, UUID entitlementId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM integration.provisioning_task t
                    WHERE t.tenant_id = ? AND t.subject_kind = 'DESIRED_GRANT'
                      AND t.subject_id = ? AND t.operation_type = 'ADD_GRANT'
                      AND t.state = 'SUCCEEDED'
                      AND (NULLIF(t.payload ->> 'principalId', '') IS NULL
                           OR t.payload ->> 'entitlementId' IS DISTINCT FROM ?
                           OR NULLIF(t.payload ->> 'providerEntitlementId', '') IS NULL
                           OR NULLIF(t.payload ->> 'providerPrincipalId', '') IS NULL))
                """, Boolean.class, tenant.tenantId(), desiredGrantId, entitlementId.toString()));
    }

    @Override
    public List<CleanupTarget> cleanupTargetsPage(
            TenantContext tenant, UUID desiredGrantId, UUID applicationTargetId,
            UUID entitlementId, UUID observedAndTechnicalPrincipalId,
            CleanupCursor after, int pageSize) {
        if (pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("cleanup page size must be 1..100");
        }
        // Same-cause history is evidence of the route used. A final absent
        // cause can also use exact governed tuple history and mapped observation.
        String history = """
                SELECT j.connector_binding_id AS binding_id, t.contract_id, t.contract_version,
                       t.payload ->> 'providerEntitlementId' AS provider_entitlement,
                       t.payload ->> 'providerEntitlementVersion' AS provider_version,
                       t.payload ->> 'providerPrincipalId' AS provider_principal,
                       t.payload ->> 'principalId' AS governed_principal,
                       false AS observed, t.created_at AS evidence_time, t.id AS evidence_id
                FROM integration.provisioning_task t
                JOIN integration.provisioning_job j
                  ON j.tenant_id = t.tenant_id AND j.id = t.provisioning_job_id
                WHERE t.tenant_id = ? AND t.subject_kind = 'DESIRED_GRANT'
                  AND t.operation_type = 'ADD_GRANT' AND t.state = 'SUCCEEDED'
                  AND t.payload ->> 'providerEntitlementId' IS NOT NULL
                  AND t.payload ->> 'providerPrincipalId' IS NOT NULL
                """;
        StringBuilder evidence = new StringBuilder(history).append(" AND t.subject_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenant.tenantId());
        args.add(desiredGrantId);
        if (observedAndTechnicalPrincipalId != null) {
            evidence.append(" UNION ALL ").append(history).append("""
                     AND t.payload ->> 'principalId' = ?
                     AND t.payload ->> 'entitlementId' = ?
                     AND jsonb_exists(t.payload, 'principalId')
                     AND jsonb_exists(t.payload, 'entitlementId')
                     AND EXISTS (SELECT 1 FROM integration.connector_binding b
                         WHERE b.tenant_id = j.tenant_id AND b.id = j.connector_binding_id
                           AND b.target_kind = 'APPLICATION_TARGET' AND b.target_id = ?)
                    """);
            args.add(tenant.tenantId());
            args.add(observedAndTechnicalPrincipalId.toString());
            args.add(entitlementId.toString());
            args.add(applicationTargetId);
            evidence.append("""
                     UNION ALL
                     SELECT b.id, b.contract_id, b.contract_version,
                            g.entitlement_provider_id, e.provider_version,
                            g.principal_provider_id, pm.principal_id::text,
                            true, g.observed_at, g.id
                     FROM integration.observed_grant g
                     JOIN integration.connector_binding b
                       ON b.tenant_id = g.tenant_id AND b.id = g.connector_binding_id
                      AND b.target_kind = 'APPLICATION_TARGET' AND b.target_id = ?
                      AND b.lifecycle_state = 'ACTIVE'
                     JOIN integration.connector_instance ci
                       ON ci.tenant_id = b.tenant_id AND ci.id = b.connector_instance_id
                      AND ci.lifecycle_state = 'ACTIVE'
                     JOIN integration.entitlement_observation_mapping em
                       ON em.tenant_id = g.tenant_id AND em.connector_binding_id = g.connector_binding_id
                      AND em.provider_stable_id = g.entitlement_provider_id
                      AND em.entitlement_id = ? AND em.lifecycle_state = 'ACTIVE'
                     JOIN integration.principal_observation_mapping pm
                       ON pm.tenant_id = g.tenant_id AND pm.connector_binding_id = g.connector_binding_id
                      AND pm.provider_stable_id = g.principal_provider_id
                      AND pm.principal_id = ? AND pm.lifecycle_state = 'ACTIVE'
                     LEFT JOIN integration.observed_entitlement e
                       ON e.tenant_id = g.tenant_id AND e.connector_binding_id = g.connector_binding_id
                      AND e.provider_stable_id = g.entitlement_provider_id
                     WHERE g.tenant_id = ? AND g.present
                    """);
            args.add(applicationTargetId);
            args.add(entitlementId);
            args.add(observedAndTechnicalPrincipalId);
            args.add(tenant.tenantId());
        }
        String afterPredicate = "";
        if (after != null) {
            afterPredicate = " WHERE (binding_id, provider_entitlement COLLATE \"C\", "
                    + "provider_principal COLLATE \"C\") > (?, ?, ?)";
            args.add(after.connectorBindingId());
            args.add(after.providerEntitlementId());
            args.add(after.providerPrincipalId());
        }
        args.add(pageSize + 1);
        // SQL-level route deduplication. Conflicting governed identities for one
        // provider route are surfaced as unavailable, not picked arbitrarily.
        String sql = """
                WITH evidence AS (%s),
                candidates AS (
                    SELECT *, min(governed_principal) OVER route AS min_principal,
                              max(governed_principal) OVER route AS max_principal,
                              bool_or(governed_principal IS NULL) OVER route AS missing_principal,
                              bool_or(observed) OVER route AS has_observation
                    FROM evidence %s
                    WINDOW route AS (PARTITION BY binding_id,
                        provider_entitlement COLLATE "C", provider_principal COLLATE "C")
                )
                SELECT DISTINCT ON (binding_id, provider_entitlement COLLATE "C",
                                    provider_principal COLLATE "C")
                    binding_id, contract_id, contract_version, provider_entitlement,
                    provider_version, provider_principal,
                    CASE WHEN NOT missing_principal AND min_principal = max_principal
                         THEN min_principal ELSE NULL END AS principal_id,
                    has_observation
                FROM candidates
                ORDER BY binding_id, provider_entitlement COLLATE "C",
                         provider_principal COLLATE "C", observed DESC, evidence_time DESC, evidence_id DESC
                LIMIT ?
                """.formatted(evidence, afterPredicate);
        return jdbc.query(sql, (rs, row) -> new CleanupTarget(
                new TechnicalGrantTarget(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getInt(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7) == null ? null : UUID.fromString(rs.getString(7))),
                rs.getBoolean(8)), args.toArray());
    }

    @Override
    public Optional<String> taskState(TenantContext tenant, String taskKey) {
        return jdbc.query("""
                SELECT state FROM integration.provisioning_task
                WHERE tenant_id = ? AND idempotency_key = ?
                """, (rs, row) -> rs.getString(1), tenant.tenantId(), taskKey)
                .stream().findFirst();
    }

    @Override
    public boolean createPlan(
            TenantContext tenant,
            String planKey,
            UUID desiredGrantId,
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
            List<TaskSpec> bindingTasks = entry.getValue();
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

            Map<String,UUID> taskIds = new LinkedHashMap<>();
            for (TaskSpec task : bindingTasks) {
                taskIds.put(task.taskKey(), ids.nextId());
            }

            for (TaskSpec task : bindingTasks) {
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
                            'DESIRED_GRANT', ?, ?,
                            ?, ?, ?,
                            ?::jsonb, 'READY', 0, NULL,
                            NULL, 1,
                            ?, ?, ?, ?)
                        """,
                        taskIds.get(task.taskKey()),
                        tenant.tenantId(),
                        jobId,
                        ids.nextId(),
                        task.operationType(),
                        desiredGrantId,
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

            for (TaskSpec task : bindingTasks) {
                if (task.dependsOnTaskKey() == null) continue;
                UUID parentId = taskIds.get(task.dependsOnTaskKey());
                if (parentId == null) continue;
                jdbc.update("""
                        INSERT INTO integration.provisioning_task_dependency (
                            tenant_id, provisioning_job_id,
                            task_id, depends_on_task_id, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                        tenant.tenantId(),
                        jobId,
                        taskIds.get(task.taskKey()),
                        parentId,
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
                    "provisioning payload is not JSON serializable", invalid);
        }
    }
}
