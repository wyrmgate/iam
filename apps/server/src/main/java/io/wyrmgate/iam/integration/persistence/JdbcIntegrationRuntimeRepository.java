package io.wyrmgate.iam.integration.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.integration.application.ConnectorExecutionRepository;
import io.wyrmgate.iam.integration.application.ConnectorWorkRepository;
import io.wyrmgate.iam.integration.application.IntegrationObservedAccessFactSink;
import io.wyrmgate.iam.integration.application.WorkerProtocolException;
import io.wyrmgate.iam.integration.application.WorkerRegistrationRepository;
import io.wyrmgate.iam.integration.domain.LeasedConnectorWork;
import io.wyrmgate.iam.integration.domain.ReconciliationCompleteness;
import io.wyrmgate.iam.integration.domain.WorkKind;
import io.wyrmgate.iam.integration.domain.WorkerCapability;
import io.wyrmgate.iam.integration.domain.WorkerExternalSubject;
import io.wyrmgate.iam.integration.domain.WorkerLease;
import io.wyrmgate.iam.integration.domain.WorkerRegistration;
import io.wyrmgate.iam.integration.domain.WorkerSession;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcIntegrationRuntimeRepository
        implements WorkerRegistrationRepository, ConnectorWorkRepository, ConnectorExecutionRepository {

    private static final TypeReference<Map<String,Object>> MAP_TYPE = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final IdGenerator ids;
    private final IntegrationObservedAccessFactSink observedAccessFacts;

    public JdbcIntegrationRuntimeRepository(JdbcTemplate jdbc, ObjectMapper json, IdGenerator ids) {
        this(jdbc, json, ids, IntegrationObservedAccessFactSink.NOOP);
    }

    public JdbcIntegrationRuntimeRepository(
            JdbcTemplate jdbc,
            ObjectMapper json,
            IdGenerator ids,
            IntegrationObservedAccessFactSink observedAccessFacts) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.json = Objects.requireNonNull(json, "json");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.observedAccessFacts = Objects.requireNonNull(
                observedAccessFacts, "observedAccessFacts");
    }

    @Override
    public Optional<WorkerRegistration> findEnabledByExternalSubject(WorkerExternalSubject subject) {
        List<WorkerRegistration> rows = jdbc.query("""
                SELECT id, tenant_id, issuer, subject, protocol_major_min, protocol_major_max
                FROM integration.connector_worker_registration
                WHERE external_subject_key = ? AND issuer = ? AND subject = ? AND state = 'ENABLED'
                """,
                (rs, row) -> new WorkerRegistration(
                        rs.getObject("id", UUID.class),
                        new TenantContext(rs.getObject("tenant_id", UUID.class)),
                        new WorkerExternalSubject(rs.getString("issuer"), rs.getString("subject")),
                        true,
                        rs.getInt("protocol_major_min"),
                        rs.getInt("protocol_major_max")),
                subjectKey(subject), subject.issuer(), subject.subject());
        return rows.stream().findFirst();
    }

    @Override
    public List<WorkerPermission> findPermissions(UUID workerRegistrationId) {
        return jdbc.query("""
                SELECT runtime_id, runtime_version, capability, contract_id, contract_version
                FROM integration.connector_worker_runtime_permission
                WHERE worker_registration_id = ?
                ORDER BY runtime_id, runtime_version, capability, contract_id, contract_version
                """,
                (rs, row) -> new WorkerPermission(
                        rs.getString("runtime_id"),
                        rs.getString("runtime_version"),
                        WorkerCapability.valueOf(rs.getString("capability")),
                        rs.getString("contract_id"),
                        rs.getInt("contract_version")),
                workerRegistrationId);
    }

    @Override
    public WorkerSession insertSession(
            WorkerRegistration worker,
            String workerInstanceId,
            int protocolMajor,
            Instant createdAt,
            Instant expiresAt,
            List<WorkerSession.NegotiatedRuntime> runtimes) {
        UUID sessionId = ids.nextId();
        jdbc.update("""
                INSERT INTO integration.connector_worker_session (
                    id, tenant_id, worker_registration_id, worker_instance_id,
                    selected_protocol_major, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                sessionId, worker.tenant().tenantId(), worker.id(), workerInstanceId,
                protocolMajor, Timestamp.from(createdAt), Timestamp.from(expiresAt));
        for (WorkerSession.NegotiatedRuntime runtime : runtimes) {
            for (WorkerCapability capability : runtime.capabilities()) {
                jdbc.update("""
                        INSERT INTO integration.connector_worker_session_capability (
                            tenant_id, session_id, runtime_id, runtime_version, capability)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                        worker.tenant().tenantId(), sessionId, runtime.runtimeId(),
                        runtime.runtimeVersion(), capability.name());
            }
            for (WorkerSession.ContractSupport contract : runtime.contracts()) {
                for (Integer version : contract.versions()) {
                    jdbc.update("""
                            INSERT INTO integration.connector_worker_session_contract (
                                tenant_id, session_id, runtime_id, runtime_version, contract_id, contract_version)
                            VALUES (?, ?, ?, ?, ?, ?)
                            """,
                            worker.tenant().tenantId(), sessionId, runtime.runtimeId(),
                            runtime.runtimeVersion(), contract.contractId(), version);
                }
            }
        }
        return new WorkerSession(
                sessionId, worker.id(), worker.tenant(), workerInstanceId,
                protocolMajor, expiresAt, runtimes);
    }

    @Override
    public Optional<WorkerSession> findActiveSession(
            UUID sessionId, UUID workerRegistrationId, Instant now) {
        record Base(UUID tenantId, String workerInstanceId, int protocolMajor, Instant expiresAt) {}
        List<Base> rows = jdbc.query("""
                SELECT tenant_id, worker_instance_id, selected_protocol_major, expires_at
                FROM integration.connector_worker_session
                WHERE id = ? AND worker_registration_id = ? AND expires_at > ?
                """,
                (rs, row) -> new Base(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("worker_instance_id"),
                        rs.getInt("selected_protocol_major"),
                        rs.getTimestamp("expires_at").toInstant()),
                sessionId, workerRegistrationId, Timestamp.from(now));
        if (rows.isEmpty()) return Optional.empty();
        Base base = rows.getFirst();

        record RuntimeKey(String runtimeId, String runtimeVersion) {}
        Map<RuntimeKey,List<WorkerCapability>> capabilities = new LinkedHashMap<>();
        jdbc.query("""
                SELECT runtime_id, runtime_version, capability
                FROM integration.connector_worker_session_capability
                WHERE tenant_id = ? AND session_id = ?
                ORDER BY runtime_id, runtime_version, capability
                """, rs -> {
                    RuntimeKey key = new RuntimeKey(rs.getString(1), rs.getString(2));
                    capabilities.computeIfAbsent(key, ignored -> new ArrayList<>())
                            .add(WorkerCapability.valueOf(rs.getString(3)));
                }, base.tenantId(), sessionId);

        Map<RuntimeKey,Map<String,List<Integer>>> contracts = new LinkedHashMap<>();
        jdbc.query("""
                SELECT runtime_id, runtime_version, contract_id, contract_version
                FROM integration.connector_worker_session_contract
                WHERE tenant_id = ? AND session_id = ?
                ORDER BY runtime_id, runtime_version, contract_id, contract_version
                """, rs -> {
                    RuntimeKey key = new RuntimeKey(rs.getString(1), rs.getString(2));
                    contracts.computeIfAbsent(key, ignored -> new LinkedHashMap<>())
                            .computeIfAbsent(rs.getString(3), ignored -> new ArrayList<>())
                            .add(rs.getInt(4));
                }, base.tenantId(), sessionId);

        List<WorkerSession.NegotiatedRuntime> runtimes = capabilities.entrySet().stream()
                .map(entry -> new WorkerSession.NegotiatedRuntime(
                        entry.getKey().runtimeId(), entry.getKey().runtimeVersion(),
                        entry.getValue(),
                        contracts.getOrDefault(entry.getKey(), Map.of()).entrySet().stream()
                                .map(c -> new WorkerSession.ContractSupport(c.getKey(), c.getValue()))
                                .toList()))
                .toList();
        return Optional.of(new WorkerSession(
                sessionId, workerRegistrationId, new TenantContext(base.tenantId()),
                base.workerInstanceId(), base.protocolMajor(), base.expiresAt(), runtimes));
    }

    @Override
    public List<ProvisioningCandidate> lockProvisioningCandidates(
            WorkerSession session, int limit, Instant now) {
        return jdbc.query("""
                SELECT t.id, t.operation_id, t.tenant_id, j.connector_binding_id,
                       t.operation_type, t.subject_kind, t.subject_id, t.desired_revision,
                       t.contract_id, t.contract_version, t.idempotency_key,
                       t.correlation_id, t.causation_id, t.payload::text
                FROM integration.provisioning_task t
                JOIN integration.provisioning_job j
                  ON j.tenant_id = t.tenant_id AND j.id = t.provisioning_job_id
                JOIN integration.connector_binding b
                  ON b.tenant_id = j.tenant_id AND b.id = j.connector_binding_id
                JOIN integration.connector_instance ci
                  ON ci.tenant_id = b.tenant_id AND ci.id = b.connector_instance_id
                JOIN integration.connector_worker_binding_scope ws
                  ON ws.tenant_id = b.tenant_id
                 AND ws.connector_binding_id = b.id
                 AND ws.worker_registration_id = ?
                JOIN integration.connector_worker_session_capability sc
                  ON sc.tenant_id = b.tenant_id AND sc.session_id = ?
                 AND sc.runtime_id = ci.runtime_id AND sc.runtime_version = ci.runtime_version
                 AND sc.capability = 'PROVISION'
                JOIN integration.connector_worker_session_contract sx
                  ON sx.tenant_id = b.tenant_id AND sx.session_id = ?
                 AND sx.runtime_id = ci.runtime_id AND sx.runtime_version = ci.runtime_version
                 AND sx.contract_id = t.contract_id AND sx.contract_version = t.contract_version
                WHERE t.tenant_id = ?
                  AND b.lifecycle_state = 'ACTIVE' AND ci.lifecycle_state = 'ACTIVE'
                  AND t.state IN ('READY','FAILED_RETRYABLE')
                  AND (t.next_attempt_at IS NULL OR t.next_attempt_at <= ?)
                  AND NOT EXISTS (
                      SELECT 1 FROM integration.provisioning_task_dependency d
                      JOIN integration.provisioning_task parent
                        ON parent.tenant_id = d.tenant_id
                       AND parent.provisioning_job_id = d.provisioning_job_id
                       AND parent.id = d.depends_on_task_id
                      WHERE d.tenant_id = t.tenant_id
                        AND d.provisioning_job_id = t.provisioning_job_id
                        AND d.task_id = t.id
                        AND parent.state NOT IN ('SUCCEEDED','SKIPPED')
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM platform.connector_work_lease l
                      WHERE l.tenant_id = t.tenant_id
                        AND l.work_kind = 'PROVISION'
                        AND l.work_id = t.id
                        AND l.lease_expires_at > ?
                  )
                ORDER BY COALESCE(t.next_attempt_at, t.created_at), t.created_at, t.id
                FOR UPDATE OF t SKIP LOCKED
                LIMIT ?
                """,
                (rs,row) -> new ProvisioningCandidate(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                        rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class), rs.getLong(8),
                        rs.getString(9), rs.getInt(10), rs.getString(11),
                        rs.getObject(12, UUID.class), rs.getObject(13, UUID.class),
                        readMap(rs.getString(14))),
                session.workerRegistrationId(), session.id(), session.id(),
                session.tenant().tenantId(), Timestamp.from(now), Timestamp.from(now), limit);
    }

    @Override
    public List<ReconciliationCandidate> lockReconciliationCandidates(
            WorkerSession session, int limit, Instant now) {
        return jdbc.query("""
                SELECT r.id, r.operation_id, r.tenant_id, r.connector_binding_id,
                       r.contract_id, r.contract_version, r.checkpoint_start,
                       r.correlation_id, r.causation_id, r.scope_object_class
                FROM integration.reconciliation_run r
                JOIN integration.connector_binding b
                  ON b.tenant_id = r.tenant_id AND b.id = r.connector_binding_id
                JOIN integration.connector_instance ci
                  ON ci.tenant_id = b.tenant_id AND ci.id = b.connector_instance_id
                JOIN integration.connector_worker_binding_scope ws
                  ON ws.tenant_id = b.tenant_id
                 AND ws.connector_binding_id = b.id
                 AND ws.worker_registration_id = ?
                JOIN integration.connector_worker_session_capability sc
                  ON sc.tenant_id = b.tenant_id AND sc.session_id = ?
                 AND sc.runtime_id = ci.runtime_id AND sc.runtime_version = ci.runtime_version
                 AND sc.capability = 'RECONCILE'
                JOIN integration.connector_worker_session_contract sx
                  ON sx.tenant_id = b.tenant_id AND sx.session_id = ?
                 AND sx.runtime_id = ci.runtime_id AND sx.runtime_version = ci.runtime_version
                 AND sx.contract_id = r.contract_id AND sx.contract_version = r.contract_version
                WHERE r.tenant_id = ?
                  AND r.state = 'RUNNING'
                  AND (r.next_attempt_at IS NULL OR r.next_attempt_at <= ?)
                  AND b.lifecycle_state = 'ACTIVE' AND ci.lifecycle_state = 'ACTIVE'
                  AND NOT EXISTS (
                      SELECT 1 FROM platform.connector_work_lease l
                      WHERE l.tenant_id = r.tenant_id
                        AND l.work_kind = 'RECONCILE'
                        AND l.work_id = r.id
                        AND l.lease_expires_at > ?
                  )
                ORDER BY COALESCE(r.next_attempt_at, r.started_at), r.started_at, r.id
                FOR UPDATE OF r SKIP LOCKED
                LIMIT ?
                """,
                (rs,row) -> new ReconciliationCandidate(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                        rs.getString(5), rs.getInt(6), rs.getString(7),
                        rs.getObject(8, UUID.class), rs.getObject(9, UUID.class),
                        Map.of("objectClass", rs.getString(10))),
                session.workerRegistrationId(), session.id(), session.id(),
                session.tenant().tenantId(), Timestamp.from(now), Timestamp.from(now), limit);
    }

    @Override
    public List<ProvisioningCandidate> lockLocalProvisioningCandidates(
            String runtimeId,
            String runtimeVersion,
            String contractId,
            int contractVersion,
            int limit,
            Instant now) {
        return jdbc.query("""
                SELECT t.id, t.operation_id, t.tenant_id, j.connector_binding_id,
                       t.operation_type, t.subject_kind, t.subject_id, t.desired_revision,
                       t.contract_id, t.contract_version, t.idempotency_key,
                       t.correlation_id, t.causation_id, t.payload::text
                FROM integration.provisioning_task t
                JOIN integration.provisioning_job j
                  ON j.tenant_id = t.tenant_id AND j.id = t.provisioning_job_id
                JOIN integration.connector_binding b
                  ON b.tenant_id = j.tenant_id AND b.id = j.connector_binding_id
                JOIN integration.connector_instance ci
                  ON ci.tenant_id = b.tenant_id AND ci.id = b.connector_instance_id
                WHERE ci.runtime_id = ?
                  AND ci.runtime_version = ?
                  AND t.contract_id = ?
                  AND t.contract_version = ?
                  AND b.contract_id = t.contract_id
                  AND b.contract_version = t.contract_version
                  AND b.lifecycle_state = 'ACTIVE'
                  AND ci.lifecycle_state = 'ACTIVE'
                  AND t.state IN ('READY','FAILED_RETRYABLE')
                  AND (t.next_attempt_at IS NULL OR t.next_attempt_at <= ?)
                  AND NOT EXISTS (
                      SELECT 1 FROM integration.provisioning_task_dependency d
                      JOIN integration.provisioning_task parent
                        ON parent.tenant_id = d.tenant_id
                       AND parent.provisioning_job_id = d.provisioning_job_id
                       AND parent.id = d.depends_on_task_id
                      WHERE d.tenant_id = t.tenant_id
                        AND d.provisioning_job_id = t.provisioning_job_id
                        AND d.task_id = t.id
                        AND parent.state NOT IN ('SUCCEEDED','SKIPPED')
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM platform.connector_work_lease l
                      WHERE l.tenant_id = t.tenant_id
                        AND l.work_kind = 'PROVISION'
                        AND l.work_id = t.id
                        AND l.lease_expires_at > ?
                  )
                ORDER BY COALESCE(t.next_attempt_at, t.created_at), t.created_at, t.id
                FOR UPDATE OF t SKIP LOCKED
                LIMIT ?
                """,
                (rs,row) -> new ProvisioningCandidate(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                        rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class), rs.getLong(8),
                        rs.getString(9), rs.getInt(10), rs.getString(11),
                        rs.getObject(12, UUID.class), rs.getObject(13, UUID.class),
                        readMap(rs.getString(14))),
                runtimeId, runtimeVersion, contractId, contractVersion,
                Timestamp.from(now), Timestamp.from(now), limit);
    }

    @Override
    public List<ReconciliationCandidate> lockLocalReconciliationCandidates(
            String runtimeId,
            String runtimeVersion,
            String contractId,
            int contractVersion,
            int limit,
            Instant now) {
        return jdbc.query("""
                SELECT r.id, r.operation_id, r.tenant_id, r.connector_binding_id,
                       r.contract_id, r.contract_version, r.checkpoint_start,
                       r.correlation_id, r.causation_id, r.scope_object_class
                FROM integration.reconciliation_run r
                JOIN integration.connector_binding b
                  ON b.tenant_id = r.tenant_id AND b.id = r.connector_binding_id
                JOIN integration.connector_instance ci
                  ON ci.tenant_id = b.tenant_id AND ci.id = b.connector_instance_id
                WHERE ci.runtime_id = ?
                  AND ci.runtime_version = ?
                  AND r.contract_id = ?
                  AND r.contract_version = ?
                  AND b.contract_id = r.contract_id
                  AND b.contract_version = r.contract_version
                  AND r.state = 'RUNNING'
                  AND (r.next_attempt_at IS NULL OR r.next_attempt_at <= ?)
                  AND b.lifecycle_state = 'ACTIVE'
                  AND ci.lifecycle_state = 'ACTIVE'
                  AND NOT EXISTS (
                      SELECT 1 FROM platform.connector_work_lease l
                      WHERE l.tenant_id = r.tenant_id
                        AND l.work_kind = 'RECONCILE'
                        AND l.work_id = r.id
                        AND l.lease_expires_at > ?
                  )
                ORDER BY COALESCE(r.next_attempt_at, r.started_at), r.started_at, r.id
                FOR UPDATE OF r SKIP LOCKED
                LIMIT ?
                """,
                (rs,row) -> new ReconciliationCandidate(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                        rs.getString(5), rs.getInt(6), rs.getString(7),
                        rs.getObject(8, UUID.class), rs.getObject(9, UUID.class),
                        Map.of("objectClass", rs.getString(10))),
                runtimeId, runtimeVersion, contractId, contractVersion,
                Timestamp.from(now), Timestamp.from(now), limit);
    }

    @Override
    public Optional<ExecutionConfiguration> findExecutionConfiguration(
            TenantContext tenant,
            UUID connectorBindingId) {
        return jdbc.query("""
                SELECT b.id, ci.id, ci.connector_type, ci.runtime_id, ci.runtime_version,
                       ci.configuration_version, ci.configuration_json::text, ci.secret_reference,
                       b.contract_id, b.contract_version,
                       b.supports_complete_principal_discovery,
                       b.supports_complete_entitlement_discovery,
                       b.supports_complete_grant_discovery
                FROM integration.connector_binding b
                JOIN integration.connector_instance ci
                  ON ci.tenant_id = b.tenant_id AND ci.id = b.connector_instance_id
                WHERE b.tenant_id = ? AND b.id = ?
                  AND b.lifecycle_state = 'ACTIVE' AND ci.lifecycle_state = 'ACTIVE'
                """,
                (rs,row) -> new ExecutionConfiguration(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6),
                        readMap(rs.getString(7)), rs.getString(8), rs.getString(9),
                        rs.getInt(10), rs.getBoolean(11), rs.getBoolean(12), rs.getBoolean(13)),
                tenant.tenantId(), connectorBindingId).stream().findFirst();
    }

    @Override
    public int nextObservationSequence(TenantContext tenant, UUID reconciliationRunId) {
        Integer value = jdbc.queryForObject("""
                SELECT COALESCE(MAX(sequence), -1) + 1
                FROM integration.reconciliation_observation_batch
                WHERE tenant_id = ? AND reconciliation_run_id = ?
                """, Integer.class, tenant.tenantId(), reconciliationRunId);
        return value == null ? 0 : value;
    }

    @Override
    public void supersedeProvisioning(
            UUID tenantId, UUID taskId, long expectedRevision, Instant now) {
        jdbc.update("""
                UPDATE integration.provisioning_task
                SET state = 'SUPERSEDED', failure_code = 'desired_revision_stale',
                    revision = revision + 1, updated_at = ?
                WHERE tenant_id = ? AND id = ? AND desired_revision = ?
                  AND state IN ('READY','FAILED_RETRYABLE')
                """, Timestamp.from(now), tenantId, taskId, expectedRevision);
    }

    @Override
    public LeasedConnectorWork leaseProvisioning(
            WorkerSession session, ProvisioningCandidate c, Instant now, Duration duration) {
        WorkerLease lease = lease(session, WorkKind.PROVISION, c.taskId(), now, duration);
        jdbc.update("""
                UPDATE integration.provisioning_task
                SET state = 'RUNNING', attempt_count = attempt_count + 1,
                    revision = revision + 1, updated_at = ?
                WHERE tenant_id = ? AND id = ?
                """, Timestamp.from(now), c.tenantId(), c.taskId());
        return new LeasedConnectorWork(
                c.taskId(), c.operationId(), lease, new TenantContext(c.tenantId()),
                c.connectorBindingId(), WorkKind.PROVISION, c.contractId(), c.contractVersion(),
                c.desiredRevision(), null, c.idempotencyKey(), c.correlationId(), c.causationId(), c.payload());
    }

    @Override
    public LeasedConnectorWork leaseReconciliation(
            WorkerSession session, ReconciliationCandidate c, Instant now, Duration duration) {
        WorkerLease lease = lease(session, WorkKind.RECONCILE, c.runId(), now, duration);
        return new LeasedConnectorWork(
                c.runId(), c.operationId(), lease, new TenantContext(c.tenantId()),
                c.connectorBindingId(), WorkKind.RECONCILE, c.contractId(), c.contractVersion(),
                null, c.checkpoint(), "reconcile:" + c.operationId(),
                c.correlationId(), c.causationId(), c.payload());
    }

    private WorkerLease lease(
            WorkerSession session, WorkKind kind, UUID workId, Instant now, Duration duration) {
        UUID leaseId = ids.nextId();
        Instant expires = now.plus(duration);
        return jdbc.queryForObject("""
                INSERT INTO platform.connector_work_lease (
                    tenant_id, work_kind, work_id, execution_owner_id, lease_id, lease_epoch,
                    claimed_at, lease_expires_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?)
                ON CONFLICT (tenant_id, work_kind, work_id) DO UPDATE
                SET execution_owner_id = EXCLUDED.execution_owner_id,
                    lease_id = EXCLUDED.lease_id,
                    lease_epoch = platform.connector_work_lease.lease_epoch + 1,
                    claimed_at = EXCLUDED.claimed_at,
                    lease_expires_at = EXCLUDED.lease_expires_at,
                    updated_at = EXCLUDED.updated_at
                WHERE platform.connector_work_lease.lease_expires_at <= ?
                RETURNING lease_id, lease_epoch, lease_expires_at
                """,
                (rs,row) -> new WorkerLease(
                        rs.getObject(1, UUID.class), rs.getLong(2), rs.getTimestamp(3).toInstant()),
                session.tenant().tenantId(), kind.name(), workId, session.id(), leaseId,
                Timestamp.from(now), Timestamp.from(expires), Timestamp.from(now), Timestamp.from(now));
    }

    @Override
    public WorkerLease renewLease(
            WorkerSession session, UUID workId, UUID leaseId, long leaseEpoch,
            Instant now, Duration duration) {
        List<WorkerLease> rows = jdbc.query("""
                UPDATE platform.connector_work_lease
                SET lease_expires_at = ?, updated_at = ?
                WHERE tenant_id = ? AND work_id = ? AND execution_owner_id = ?
                  AND lease_id = ? AND lease_epoch = ? AND lease_expires_at > ?
                RETURNING lease_id, lease_epoch, lease_expires_at
                """,
                (rs,row) -> new WorkerLease(
                        rs.getObject(1, UUID.class), rs.getLong(2), rs.getTimestamp(3).toInstant()),
                Timestamp.from(now.plus(duration)), Timestamp.from(now),
                session.tenant().tenantId(), workId, session.id(), leaseId, leaseEpoch, Timestamp.from(now));
        if (rows.isEmpty()) throw staleLease();
        return rows.getFirst();
    }

    @Override
    public ObservationBatchResult appendProviderObservations(
            WorkerSession session, UUID workId, UUID leaseId, long leaseEpoch,
            UUID batchId, int sequence, String requestFingerprint,
            List<ProviderObservation> observations, Instant now) {
        requireCurrentLease(session, WorkKind.RECONCILE, workId, leaseId, leaseEpoch, now, true);
        String scope = jdbc.queryForObject("""
                SELECT scope_object_class
                FROM integration.reconciliation_run
                WHERE tenant_id = ? AND id = ? AND state = 'RUNNING'
                """, String.class, session.tenant().tenantId(), workId);
        if (scope == null) {
            throw new WorkerProtocolException("work_not_running", "reconciliation run is not running");
        }
        for (ProviderObservation observation : observations) {
            if (!scope.equals(observation.objectClass())) {
                throw new WorkerProtocolException(
                        "observation_object_class_mismatch",
                        "observation objectClass must match reconciliation scope");
            }
        }

        List<String> existing = jdbc.query("""
                SELECT request_fingerprint
                FROM integration.reconciliation_observation_batch
                WHERE tenant_id = ? AND reconciliation_run_id = ? AND batch_id = ?
                """, (rs,row) -> rs.getString(1),
                session.tenant().tenantId(), workId, batchId);
        if (!existing.isEmpty()) {
            if (existing.getFirst().equals(requestFingerprint)) return ObservationBatchResult.REPLAY;
            throw new WorkerProtocolException(
                    "observation_batch_conflict",
                    "batchId was already used with different content");
        }
        try {
            jdbc.update("""
                    INSERT INTO integration.reconciliation_observation_batch (
                        tenant_id, reconciliation_run_id, batch_id, sequence,
                        request_fingerprint, received_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    session.tenant().tenantId(), workId, batchId, sequence,
                    requestFingerprint, Timestamp.from(now));
        } catch (DataIntegrityViolationException conflict) {
            throw new WorkerProtocolException(
                    "observation_sequence_conflict",
                    "observation sequence was already used");
        }

        for (ProviderObservation observation : observations) {
            switch (scope) {
                case "PRINCIPAL" -> jdbc.update("""
                        INSERT INTO integration.reconciliation_principal_staging (
                            id, tenant_id, reconciliation_run_id, batch_id,
                            provider_stable_id, provider_version, observed_state, observed_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                        ON CONFLICT (tenant_id, reconciliation_run_id, provider_stable_id) DO UPDATE
                        SET batch_id = EXCLUDED.batch_id,
                            provider_version = EXCLUDED.provider_version,
                            observed_state = EXCLUDED.observed_state,
                            observed_at = EXCLUDED.observed_at
                        """,
                        ids.nextId(), session.tenant().tenantId(), workId, batchId,
                        observation.providerStableId(), observation.providerVersion(),
                        writeJson(observation.observedState()), Timestamp.from(now));
                case "ENTITLEMENT" -> jdbc.update("""
                        INSERT INTO integration.reconciliation_entitlement_staging (
                            id, tenant_id, reconciliation_run_id, batch_id,
                            provider_stable_id, provider_version, observed_state, observed_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                        ON CONFLICT (tenant_id, reconciliation_run_id, provider_stable_id) DO UPDATE
                        SET batch_id = EXCLUDED.batch_id,
                            provider_version = EXCLUDED.provider_version,
                            observed_state = EXCLUDED.observed_state,
                            observed_at = EXCLUDED.observed_at
                        """,
                        ids.nextId(), session.tenant().tenantId(), workId, batchId,
                        observation.providerStableId(), observation.providerVersion(),
                        writeJson(observation.observedState()), Timestamp.from(now));
                case "GRANT" -> {
                    String principalProviderId =
                            requiredObservationText(observation.observedState(), "principalProviderId");
                    String entitlementProviderId =
                            requiredObservationText(observation.observedState(), "entitlementProviderId");
                    jdbc.update("""
                            INSERT INTO integration.reconciliation_grant_staging (
                                id, tenant_id, reconciliation_run_id, batch_id,
                                provider_stable_id, provider_version,
                                principal_provider_id, entitlement_provider_id,
                                observed_state, observed_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                            ON CONFLICT (
                                tenant_id, reconciliation_run_id,
                                principal_provider_id, entitlement_provider_id
                            ) DO UPDATE
                            SET batch_id = EXCLUDED.batch_id,
                                provider_stable_id = EXCLUDED.provider_stable_id,
                                provider_version = EXCLUDED.provider_version,
                                observed_state = EXCLUDED.observed_state,
                                observed_at = EXCLUDED.observed_at
                            """,
                            ids.nextId(), session.tenant().tenantId(), workId, batchId,
                            observation.providerStableId(), observation.providerVersion(),
                            principalProviderId, entitlementProviderId,
                            writeJson(observation.observedState()), Timestamp.from(now));
                }
                default -> throw new WorkerProtocolException(
                        "unsupported_object_class",
                        "unsupported reconciliation object class");
            }
        }
        return ObservationBatchResult.ACCEPTED;
    }

    @Override
    public CompletionResult complete(
            WorkerSession session, UUID workId, UUID leaseId, long leaseEpoch,
            WorkCompletion completion, String completionFingerprint, Instant now) {
        List<String> kinds = jdbc.query("""
                SELECT work_kind
                FROM platform.connector_work_lease
                WHERE tenant_id = ? AND work_id = ? AND execution_owner_id = ?
                  AND lease_id = ? AND lease_epoch = ?
                """, (rs,row) -> rs.getString(1),
                session.tenant().tenantId(), workId, session.id(), leaseId, leaseEpoch);
        if (kinds.isEmpty()) throw staleLease();
        WorkKind kind = WorkKind.valueOf(kinds.getFirst());
        return kind == WorkKind.PROVISION
                ? completeProvisioning(session, workId, leaseId, leaseEpoch, completion, completionFingerprint, now)
                : completeReconciliation(session, workId, leaseId, leaseEpoch, completion, completionFingerprint, now);
    }

    private CompletionResult completeProvisioning(
            WorkerSession session, UUID taskId, UUID leaseId, long leaseEpoch,
            WorkCompletion completion, String fingerprint, Instant now) {
        List<String> existing = jdbc.query("""
                SELECT completion_fingerprint
                FROM integration.provisioning_attempt
                WHERE tenant_id = ? AND task_id = ? AND lease_epoch = ?
                """, (rs,row) -> rs.getString(1),
                session.tenant().tenantId(), taskId, leaseEpoch);
        if (!existing.isEmpty()) {
            if (existing.getFirst().equals(fingerprint)) return CompletionResult.REPLAY;
            throw new WorkerProtocolException("completion_conflict", "lease generation already completed with different content");
        }
        LeaseRow lease = requireCurrentLease(
                session, WorkKind.PROVISION, taskId, leaseId, leaseEpoch, now, true);
        record Task(int attempt, UUID correlation, UUID causation) {}
        List<Task> tasks = jdbc.query("""
                SELECT attempt_count, correlation_id, causation_id
                FROM integration.provisioning_task
                WHERE tenant_id = ? AND id = ? AND state = 'RUNNING'
                """,
                (rs,row) -> new Task(rs.getInt(1), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class)),
                session.tenant().tenantId(), taskId);
        if (tasks.isEmpty()) throw new WorkerProtocolException("work_not_running", "provisioning task is not running");
        Task task = tasks.getFirst();
        jdbc.update("""
                INSERT INTO integration.provisioning_attempt (
                    id, tenant_id, task_id, attempt_number, lease_id, lease_epoch,
                    started_at, completed_at, outcome, failure_category,
                    provider_error_code, provider_request_id, provider_object_id, provider_version,
                    metadata, completion_fingerprint, correlation_id, causation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """,
                ids.nextId(), session.tenant().tenantId(), taskId, task.attempt(),
                leaseId, leaseEpoch, Timestamp.from(lease.claimedAt()), Timestamp.from(now),
                completion.outcome(), completion.failureCategory(), completion.providerErrorCode(),
                completion.providerRequestId(), completion.providerObjectId(), completion.providerVersion(),
                writeJson(completion.metadata()), fingerprint, task.correlation(), task.causation());

        String state = switch (completion.outcome()) {
            case "SUCCEEDED" -> "SUCCEEDED";
            case "FAILED_RETRYABLE" -> "FAILED_RETRYABLE";
            case "FAILED_FINAL" -> "FAILED_FINAL";
            case "SUPERSEDED" -> "SUPERSEDED";
            case "SKIPPED" -> "SKIPPED";
            default -> throw new WorkerProtocolException("invalid_outcome", "unsupported work outcome");
        };
        Instant next = "FAILED_RETRYABLE".equals(state)
                ? now.plusSeconds(completion.retryAfterSeconds() == null ? 30 : completion.retryAfterSeconds())
                : null;
        jdbc.update("""
                UPDATE integration.provisioning_task
                SET state = ?, next_attempt_at = ?, failure_code = ?,
                    revision = revision + 1, updated_at = ?
                WHERE tenant_id = ? AND id = ?
                """,
                state, next == null ? null : Timestamp.from(next),
                completion.providerErrorCode(), Timestamp.from(now),
                session.tenant().tenantId(), taskId);
        return CompletionResult.ACCEPTED;
    }

    private CompletionResult completeReconciliation(
            WorkerSession session, UUID runId, UUID leaseId, long leaseEpoch,
            WorkCompletion completion, String fingerprint, Instant now) {
        record Existing(Long epoch, String fingerprint) {}
        List<Existing> existing = jdbc.query("""
                SELECT completion_lease_epoch, completion_fingerprint
                FROM integration.reconciliation_run
                WHERE tenant_id = ? AND id = ?
                """,
                (rs,row) -> new Existing(
                        rs.getObject(1) == null ? null : rs.getLong(1), rs.getString(2)),
                session.tenant().tenantId(), runId);
        if (existing.isEmpty()) {
            throw new WorkerProtocolException("work_not_found", "reconciliation work does not exist");
        }
        if (existing.getFirst().epoch() != null) {
            if (existing.getFirst().epoch() == leaseEpoch
                    && Objects.equals(existing.getFirst().fingerprint(), fingerprint)) {
                return CompletionResult.REPLAY;
            }
            throw new WorkerProtocolException(
                    "completion_conflict", "reconciliation run is already completed");
        }
        requireCurrentLease(session, WorkKind.RECONCILE, runId, leaseId, leaseEpoch, now, true);

        record Run(
                UUID bindingId,
                String objectClass,
                long configurationVersion,
                String runtimeId,
                String runtimeVersion,
                String contractId,
                int contractVersion,
                long revision,
                UUID correlationId) {}
        Run run = jdbc.queryForObject("""
                SELECT connector_binding_id, scope_object_class, configuration_version,
                       runtime_id, runtime_version, contract_id, contract_version,
                       revision, correlation_id
                FROM integration.reconciliation_run
                WHERE tenant_id = ? AND id = ? AND state = 'RUNNING'
                """,
                (rs,row) -> new Run(
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getInt(7),
                        rs.getLong(8), rs.getObject(9, UUID.class)),
                session.tenant().tenantId(), runId);
        if (run == null) {
            throw new WorkerProtocolException("work_not_running", "reconciliation run is not running");
        }

        ReconciliationCompleteness reported = completion.discoveryCoverage() == null
                ? ReconciliationCompleteness.UNKNOWN : completion.discoveryCoverage();
        String completenessColumn = switch (run.objectClass()) {
            case "PRINCIPAL" -> "supports_complete_principal_discovery";
            case "ENTITLEMENT" -> "supports_complete_entitlement_discovery";
            case "GRANT" -> "supports_complete_grant_discovery";
            default -> throw new WorkerProtocolException(
                    "unsupported_object_class", "unsupported reconciliation object class");
        };
        Boolean contractStillTrusted = jdbc.queryForObject("""
                SELECT (
                    CASE ?
                        WHEN 'supports_complete_principal_discovery'
                            THEN b.supports_complete_principal_discovery
                        WHEN 'supports_complete_entitlement_discovery'
                            THEN b.supports_complete_entitlement_discovery
                        WHEN 'supports_complete_grant_discovery'
                            THEN b.supports_complete_grant_discovery
                        ELSE false
                    END
                    AND b.contract_id = ?
                    AND b.contract_version = ?
                    AND ci.configuration_version = ?
                    AND ci.runtime_id = ?
                    AND ci.runtime_version = ?
                    AND b.lifecycle_state = 'ACTIVE'
                    AND ci.lifecycle_state = 'ACTIVE'
                )
                FROM integration.connector_binding b
                JOIN integration.connector_instance ci
                  ON ci.tenant_id = b.tenant_id AND ci.id = b.connector_instance_id
                WHERE b.tenant_id = ? AND b.id = ?
                """,
                Boolean.class,
                completenessColumn,
                run.contractId(), run.contractVersion(), run.configurationVersion(),
                run.runtimeId(), run.runtimeVersion(),
                session.tenant().tenantId(), run.bindingId());
        boolean trustedComplete = "SUCCEEDED".equals(completion.outcome())
                && reported == ReconciliationCompleteness.COMPLETE
                && Boolean.TRUE.equals(contractStillTrusted);
        ReconciliationCompleteness effective = trustedComplete
                ? ReconciliationCompleteness.COMPLETE
                : (reported == ReconciliationCompleteness.PARTIAL
                    ? ReconciliationCompleteness.PARTIAL
                    : ReconciliationCompleteness.UNKNOWN);

        switch (run.objectClass()) {
            case "PRINCIPAL" -> materializePrincipals(
                    session, runId, run.bindingId(), effective, now);
            case "ENTITLEMENT" -> materializeEntitlements(
                    session, runId, run.bindingId(), effective, now);
            case "GRANT" -> materializeGrants(
                    session, runId, run.bindingId(), effective, now);
            default -> throw new WorkerProtocolException(
                    "unsupported_object_class", "unsupported reconciliation object class");
        }

        String state = switch (completion.outcome()) {
            case "SUCCEEDED" -> "SUCCEEDED";
            case "FAILED_RETRYABLE", "FAILED_FINAL" -> "FAILED";
            case "SUPERSEDED", "SKIPPED" -> "CANCELLED";
            default -> throw new WorkerProtocolException(
                    "invalid_outcome", "unsupported work outcome");
        };
        jdbc.update("""
                UPDATE integration.reconciliation_run
                SET state = ?, reported_coverage = ?, effective_completeness = ?,
                    checkpoint_end = ?, failure_code = ?, completion_lease_epoch = ?,
                    completion_fingerprint = ?, completed_at = ?, updated_at = ?,
                    revision = revision + 1
                WHERE tenant_id = ? AND id = ? AND state = 'RUNNING'
                """,
                state, reported.name(), effective.name(), completion.nextCheckpoint(),
                completion.providerErrorCode(), leaseEpoch, fingerprint,
                Timestamp.from(now), Timestamp.from(now),
                session.tenant().tenantId(), runId);
        if ("ENTITLEMENT".equals(run.objectClass()) || "GRANT".equals(run.objectClass())) {
            observedAccessFacts.inputChanged(
                    session.tenant(),
                    run.bindingId(),
                    IntegrationObservedAccessFactSink.SourceKind.RECONCILIATION_RUN,
                    runId,
                    run.revision() + 1,
                    now,
                    run.correlationId());
        }
        return CompletionResult.ACCEPTED;
    }

    private void materializePrincipals(
            WorkerSession session,
            UUID runId,
            UUID bindingId,
            ReconciliationCompleteness effective,
            Instant now) {
        record Row(String stableId, String version, String state, Instant observedAt) {}
        List<Row> staged = jdbc.query("""
                SELECT provider_stable_id, provider_version, observed_state::text, observed_at
                FROM integration.reconciliation_principal_staging
                WHERE tenant_id = ? AND reconciliation_run_id = ?
                ORDER BY provider_stable_id
                """,
                (rs,row) -> new Row(
                        rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant()),
                session.tenant().tenantId(), runId);
        for (Row row : staged) {
            jdbc.update("""
                    INSERT INTO integration.observed_principal (
                        id, tenant_id, connector_binding_id, provider_stable_id, provider_version,
                        observed_state, present, last_observed_run_id, observed_at, absent_at)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, true, ?, ?, NULL)
                    ON CONFLICT (tenant_id, connector_binding_id, provider_stable_id) DO UPDATE
                    SET provider_version = EXCLUDED.provider_version,
                        observed_state = EXCLUDED.observed_state,
                        present = true,
                        last_observed_run_id = EXCLUDED.last_observed_run_id,
                        observed_at = EXCLUDED.observed_at,
                        absent_at = NULL
                    """,
                    ids.nextId(), session.tenant().tenantId(), bindingId,
                    row.stableId(), row.version(), row.state(),
                    runId, Timestamp.from(row.observedAt()));
        }
        if (effective == ReconciliationCompleteness.COMPLETE) {
            markUnseenAbsent(
                    "observed_principal",
                    "reconciliation_principal_staging",
                    session.tenant().tenantId(), bindingId, runId, now);
        }
    }

    private void materializeEntitlements(
            WorkerSession session,
            UUID runId,
            UUID bindingId,
            ReconciliationCompleteness effective,
            Instant now) {
        record Row(String stableId, String version, String state, Instant observedAt) {}
        List<Row> staged = jdbc.query("""
                SELECT provider_stable_id, provider_version, observed_state::text, observed_at
                FROM integration.reconciliation_entitlement_staging
                WHERE tenant_id = ? AND reconciliation_run_id = ?
                ORDER BY provider_stable_id
                """,
                (rs,row) -> new Row(
                        rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant()),
                session.tenant().tenantId(), runId);
        for (Row row : staged) {
            jdbc.update("""
                    INSERT INTO integration.observed_entitlement (
                        id, tenant_id, connector_binding_id, provider_stable_id, provider_version,
                        observed_state, present, last_observed_run_id, observed_at, absent_at)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, true, ?, ?, NULL)
                    ON CONFLICT (tenant_id, connector_binding_id, provider_stable_id) DO UPDATE
                    SET provider_version = EXCLUDED.provider_version,
                        observed_state = EXCLUDED.observed_state,
                        present = true,
                        last_observed_run_id = EXCLUDED.last_observed_run_id,
                        observed_at = EXCLUDED.observed_at,
                        absent_at = NULL
                    """,
                    ids.nextId(), session.tenant().tenantId(), bindingId,
                    row.stableId(), row.version(), row.state(),
                    runId, Timestamp.from(row.observedAt()));
        }
        if (effective == ReconciliationCompleteness.COMPLETE) {
            markUnseenAbsent(
                    "observed_entitlement",
                    "reconciliation_entitlement_staging",
                    session.tenant().tenantId(), bindingId, runId, now);
        }
    }

    private void materializeGrants(
            WorkerSession session,
            UUID runId,
            UUID bindingId,
            ReconciliationCompleteness effective,
            Instant now) {
        record Row(
                String stableId,
                String version,
                String principalProviderId,
                String entitlementProviderId,
                String state,
                Instant observedAt) {}
        List<Row> staged = jdbc.query("""
                SELECT provider_stable_id, provider_version,
                       principal_provider_id, entitlement_provider_id,
                       observed_state::text, observed_at
                FROM integration.reconciliation_grant_staging
                WHERE tenant_id = ? AND reconciliation_run_id = ?
                ORDER BY provider_stable_id
                """,
                (rs,row) -> new Row(
                        rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getTimestamp(6).toInstant()),
                session.tenant().tenantId(), runId);
        for (Row row : staged) {
            jdbc.update("""
                    INSERT INTO integration.observed_grant (
                        id, tenant_id, connector_binding_id, provider_stable_id, provider_version,
                        principal_provider_id, entitlement_provider_id,
                        observed_state, present, last_observed_run_id, observed_at, absent_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, true, ?, ?, NULL)
                    ON CONFLICT (
                        tenant_id, connector_binding_id,
                        principal_provider_id, entitlement_provider_id
                    ) DO UPDATE
                    SET provider_stable_id = EXCLUDED.provider_stable_id,
                        provider_version = EXCLUDED.provider_version,
                        observed_state = EXCLUDED.observed_state,
                        present = true,
                        last_observed_run_id = EXCLUDED.last_observed_run_id,
                        observed_at = EXCLUDED.observed_at,
                        absent_at = NULL
                    """,
                    ids.nextId(), session.tenant().tenantId(), bindingId,
                    row.stableId(), row.version(),
                    row.principalProviderId(), row.entitlementProviderId(), row.state(),
                    runId, Timestamp.from(row.observedAt()));
        }
        if (effective == ReconciliationCompleteness.COMPLETE) {
            markUnseenAbsent(
                    "observed_grant",
                    "reconciliation_grant_staging",
                    session.tenant().tenantId(), bindingId, runId, now);
        }
    }

    private void markUnseenAbsent(
            String observedTable,
            String stagingTable,
            UUID tenantId,
            UUID bindingId,
            UUID runId,
            Instant now) {
        jdbc.update("""
                UPDATE integration.%s o
                SET present = false, absent_at = ?, last_observed_run_id = ?
                WHERE o.tenant_id = ? AND o.connector_binding_id = ? AND o.present
                  AND NOT EXISTS (
                      SELECT 1 FROM integration.%s s
                      WHERE s.tenant_id = o.tenant_id
                        AND s.reconciliation_run_id = ?
                        AND s.provider_stable_id = o.provider_stable_id
                  )
                """.formatted(observedTable, stagingTable),
                Timestamp.from(now), runId, tenantId, bindingId, runId);
    }

    private static String requiredObservationText(Map<String,Object> state, String key) {
        Object value = state == null ? null : state.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new WorkerProtocolException(
                    "invalid_observation",
                    key + " must be present for GRANT observations");
        }
        return text;
    }

    private LeaseRow requireCurrentLease(
            WorkerSession session, WorkKind kind, UUID workId, UUID leaseId,
            long leaseEpoch, Instant now, boolean requireUnexpired) {
        List<LeaseRow> rows = jdbc.query("""
                SELECT claimed_at, lease_expires_at
                FROM platform.connector_work_lease
                WHERE tenant_id = ? AND work_kind = ? AND work_id = ?
                  AND execution_owner_id = ?
                  AND lease_id = ? AND lease_epoch = ?
                """,
                (rs,row) -> new LeaseRow(
                        rs.getTimestamp(1).toInstant(),
                        rs.getTimestamp(2).toInstant()),
                session.tenant().tenantId(), kind.name(), workId, session.id(),
                leaseId, leaseEpoch);
        if (rows.isEmpty()
                || (requireUnexpired && !rows.getFirst().expiresAt().isAfter(now))) {
            throw staleLease();
        }
        return rows.getFirst();
    }

    private static WorkerProtocolException staleLease() {
        return new WorkerProtocolException("stale_lease", "lease is stale or expired");
    }

    private Map<String,Object> readMap(String value) {
        try {
            return value == null ? Map.of() : json.readValue(value, MAP_TYPE);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("persisted connector JSON is invalid", invalid);
        }
    }

    private String writeJson(Map<String,Object> value) {
        try {
            return json.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("connector metadata is not JSON serializable", invalid);
        }
    }

    static String subjectKey(WorkerExternalSubject subject) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] issuer = subject.issuer().getBytes(StandardCharsets.UTF_8);
            byte[] external = subject.subject().getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(issuer.length).array());
            digest.update(issuer);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(external.length).array());
            digest.update(external);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record LeaseRow(Instant claimedAt, Instant expiresAt) {}
}
