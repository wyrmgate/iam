package io.wyrmgate.iam.integration.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.integration.application.IntegrationOperationalStatusRepository.PagePosition;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.JdbcTenantRepository;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcIntegrationOperationalStatusRepositoryTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static JdbcTemplate jdbc;
    private static JdbcTenantRepository tenants;
    private static JdbcIntegrationOperationalStatusRepository repository;

    @BeforeAll
    static void setup() {
        io.wyrmgate.iam.platform.persistence.SharedPostgresIntegrationDatabase.reset();
        var dataSource = io.wyrmgate.iam.platform.persistence.SharedPostgresIntegrationDatabase.dataSource();
        jdbc = new JdbcTemplate(dataSource);
        tenants = new JdbcTenantRepository(jdbc, new UuidV7Generator());
        repository = new JdbcIntegrationOperationalStatusRepository(jdbc);
    }
@Test
    void tenantIsolationPaginationAndStructuralRedactionHold() throws Exception {
        Instant base = Instant.parse("2026-10-12T04:00:00Z");
        UUID tenantA = tenants.create("Tenant A", base).id();
        UUID tenantB = tenants.create("Tenant B", base).id();
        UUID bindingA = binding(tenantA, base);
        UUID bindingB = binding(tenantB, base);

        UUID oldestJob = job(tenantA, bindingA, base.plusSeconds(1));
        UUID middleJob = job(tenantA, bindingA, base.plusSeconds(2));
        UUID newestJob = job(tenantA, bindingA, base.plusSeconds(3));
        UUID foreignJob = job(tenantB, bindingB, base.plusSeconds(4));

        TenantContext contextA = new TenantContext(tenantA);
        var first = repository.findProvisioningJobPage(contextA, null, 2);
        assertThat(first).extracting(value -> value.id())
                .containsExactly(newestJob, middleJob);
        var second = repository.findProvisioningJobPage(
                contextA,
                new PagePosition(first.getLast().createdAt(), first.getLast().id()),
                2);
        assertThat(second).extracting(value -> value.id()).containsExactly(oldestJob);
        assertThat(repository.findProvisioningJob(contextA, foreignJob)).isEmpty();

        UUID task = task(tenantA, newestJob, base.plusSeconds(5));
        UUID attempt = attempt(tenantA, task, base.plusSeconds(6));
        UUID run = reconciliationRun(tenantA, bindingA, base.plusSeconds(7));

        String taskJson = JSON.writeValueAsString(repository.findProvisioningTask(contextA, task).orElseThrow());
        String attemptJson = JSON.writeValueAsString(repository.findProvisioningAttempt(contextA, attempt).orElseThrow());
        String runJson = JSON.writeValueAsString(repository.findReconciliationRun(contextA, run).orElseThrow());

        assertThat(taskJson)
                .doesNotContain("super-secret-idempotency", "raw-provider-secret", "payload");
        assertThat(attemptJson)
                .doesNotContain("provider-secret-code", "provider-object-secret", "leaseId", "metadata");
        assertThat(runJson)
                .doesNotContain("checkpoint-secret", "completion-secret", "checkpointStart");
    }

    private static UUID binding(UUID tenantId, Instant now) {
        UUID connectorId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO integration.connector_instance (
                    id, tenant_id, connector_type, runtime_id, runtime_version,
                    configuration_version, configuration_json, secret_reference,
                    lifecycle_state, revision, created_at, updated_at)
                VALUES (?, ?, 'scim', 'scim', '1', 1, '{}'::jsonb, ?, 'ACTIVE', 1, ?, ?)
                """, connectorId, tenantId, "secret://connector", Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO integration.connector_binding (
                    id, tenant_id, connector_instance_id, target_kind, target_id,
                    contract_id, contract_version, supports_complete_principal_discovery,
                    lifecycle_state, revision, created_at, updated_at)
                VALUES (?, ?, ?, 'APPLICATION_TARGET', ?, 'principal.v1', 1, true,
                        'ACTIVE', 1, ?, ?)
                """, bindingId, tenantId, connectorId, UUID.randomUUID(),
                Timestamp.from(now), Timestamp.from(now));
        return bindingId;
    }

    private static UUID job(UUID tenantId, UUID bindingId, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO integration.provisioning_job (
                    id, tenant_id, connector_binding_id, state, revision,
                    correlation_id, causation_id, created_at, updated_at)
                VALUES (?, ?, ?, 'RUNNING', 1, ?, NULL, ?, ?)
                """, id, tenantId, bindingId, UUID.randomUUID(), Timestamp.from(now), Timestamp.from(now));
        return id;
    }

    private static UUID task(UUID tenantId, UUID jobId, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO integration.provisioning_task (
                    id, tenant_id, provisioning_job_id, operation_id, operation_type,
                    subject_kind, subject_id, desired_revision, idempotency_key,
                    contract_id, contract_version, payload, state, attempt_count,
                    next_attempt_at, failure_code, revision, correlation_id, causation_id,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, 'UPSERT', 'PRINCIPAL', ?, 3, 'super-secret-idempotency',
                        'principal.v1', 1, '{"raw":"raw-provider-secret"}'::jsonb,
                        'FAILED_RETRYABLE', 1, ?, 'provider_unavailable', 2, ?, NULL, ?, ?)
                """, id, tenantId, jobId, UUID.randomUUID(), UUID.randomUUID(),
                Timestamp.from(now.plusSeconds(30)), UUID.randomUUID(),
                Timestamp.from(now), Timestamp.from(now));
        return id;
    }

    private static UUID attempt(UUID tenantId, UUID taskId, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO integration.provisioning_attempt (
                    id, tenant_id, task_id, attempt_number, lease_id, lease_epoch,
                    started_at, completed_at, outcome, failure_category,
                    provider_error_code, provider_request_id, provider_object_id,
                    provider_version, metadata, completion_fingerprint,
                    correlation_id, causation_id)
                VALUES (?, ?, ?, 1, ?, 1, ?, ?, 'FAILED_RETRYABLE', 'PROVIDER',
                        'provider-secret-code', 'provider-request-secret', 'provider-object-secret',
                        'provider-version-secret', '{"secret":"metadata-secret"}'::jsonb,
                        'completion-secret', ?, NULL)
                """, id, tenantId, taskId, UUID.randomUUID(), Timestamp.from(now),
                Timestamp.from(now.plusSeconds(1)), UUID.randomUUID());
        return id;
    }

    private static UUID reconciliationRun(UUID tenantId, UUID bindingId, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO integration.reconciliation_run (
                    id, tenant_id, connector_binding_id, operation_id, scope_object_class,
                    state, reported_coverage, effective_completeness, configuration_version,
                    runtime_id, runtime_version, contract_id, contract_version,
                    checkpoint_start, checkpoint_end, next_attempt_at, failure_code,
                    completion_lease_epoch, completion_fingerprint, correlation_id, causation_id,
                    revision, started_at, completed_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'PRINCIPAL', 'FAILED', 'PARTIAL', 'PARTIAL', 1,
                        'scim', '1', 'principal.v1', 1,
                        'checkpoint-secret-start', 'checkpoint-secret-end', ?, 'provider_unavailable',
                        7, 'completion-secret', ?, NULL, 2, ?, ?, ?, ?)
                """, id, tenantId, bindingId, UUID.randomUUID(),
                Timestamp.from(now.plusSeconds(30)), UUID.randomUUID(),
                Timestamp.from(now), Timestamp.from(now.plusSeconds(1)),
                Timestamp.from(now), Timestamp.from(now.plusSeconds(1)));
        return id;
    }
}
