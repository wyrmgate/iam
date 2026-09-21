package io.wyrmgate.iam.integration.provider.scim;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.wyrmgate.iam.access.application.DesiredAccessStateQuery;
import io.wyrmgate.iam.access.application.DesiredAccessStateQuery.Freshness;
import io.wyrmgate.iam.integration.persistence.JdbcIntegrationRuntimeRepository;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.JdbcTenantRepository;
import io.wyrmgate.iam.platform.persistence.SpringTransactionExecutor;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

class ScimLocalExecutionIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");
    private static final Instant NOW = Instant.parse("2026-09-21T08:00:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static JdbcIntegrationRuntimeRepository repository;
    private static TransactionExecutor transactions;
    private static ObjectMapper json;

    private HttpServer server;
    private URI baseUri;
    private final Map<UUID,Freshness> freshness = new ConcurrentHashMap<>();

    @BeforeAll
    static void startPostgresAndMigrate() {
        POSTGRES.start();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(dataSource).load();
        flyway.migrate();
        flyway.validate();
        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new JdbcTenantRepository(jdbc, ids);
        json = new ObjectMapper().findAndRegisterModules();
        repository = new JdbcIntegrationRuntimeRepository(jdbc, json, ids);
        transactions = new SpringTransactionExecutor(
                new DataSourceTransactionManager(dataSource));
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @BeforeEach
    void setUp() throws IOException {
        freshness.clear();
        clearDatabase();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUri = URI.create(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/scim/v2/");
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void provisioningExecutesDurablyWhileStaleAndUnavailableDesiredStateNeverMutateProvider() {
        AtomicInteger providerCalls = new AtomicInteger();
        server.createContext("/scim/v2/Users", exchange -> {
            providerCalls.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                    .isEqualTo("Bearer local-test-token");
            respond(exchange, 201, """
                    {"id":"provider-1","meta":{"version":"v1"}}
                    """);
        });
        server.start();

        TenantContext tenant = tenant("local-provision");
        UUID binding = connector(tenant, true, 100);
        UUID currentSubject = ids.nextId();
        freshness.put(currentSubject, Freshness.current(1));
        UUID currentTask = provisioning(
                tenant,
                binding,
                currentSubject,
                1,
                "UPSERT_PRINCIPAL",
                Map.of("userName", "alice", "displayName", "Alice"));

        assertThat(service().executeAvailable()).isEqualTo(1);
        assertThat(taskState(currentTask)).isEqualTo("SUCCEEDED");
        assertThat(providerCalls).hasValue(1);
        assertThat(jdbc.queryForObject("""
                SELECT provider_object_id
                FROM integration.provisioning_attempt
                WHERE task_id = ?
                """, String.class, currentTask)).isEqualTo("provider-1");

        UUID staleSubject = ids.nextId();
        freshness.put(staleSubject, Freshness.current(3));
        UUID staleTask = provisioning(
                tenant,
                binding,
                staleSubject,
                2,
                "UPSERT_PRINCIPAL",
                Map.of("userName", "stale"));

        assertThat(service().executeAvailable()).isZero();
        assertThat(taskState(staleTask)).isEqualTo("SUPERSEDED");
        assertThat(providerCalls).hasValue(1);

        UUID unavailableSubject = ids.nextId();
        freshness.put(unavailableSubject, Freshness.unavailable());
        UUID unavailableTask = provisioning(
                tenant,
                binding,
                unavailableSubject,
                4,
                "UPSERT_PRINCIPAL",
                Map.of("userName", "unavailable"));

        assertThat(service().executeAvailable()).isZero();
        assertThat(taskState(unavailableTask)).isEqualTo("READY");
        assertThat(leaseCount(unavailableTask)).isZero();
        assertThat(providerCalls).hasValue(1);
    }

    @Test
    void desiredStateChangingAfterClaimPreventsProviderMutation() {
        AtomicInteger providerCalls = new AtomicInteger();
        server.createContext("/scim/v2/Users", exchange -> {
            providerCalls.incrementAndGet();
            respond(exchange, 201, "{\"id\":\"must-not-happen\"}");
        });
        server.start();

        TenantContext tenant = tenant("claim-race");
        UUID binding = connector(tenant, true, 100);
        UUID subject = ids.nextId();
        UUID task = provisioning(
                tenant,
                binding,
                subject,
                7,
                "UPSERT_PRINCIPAL",
                Map.of("userName", "race"));

        AtomicInteger reads = new AtomicInteger();
        DesiredAccessStateQuery query = (ignoredTenant, kind, subjectId) ->
                reads.incrementAndGet() == 1
                        ? Freshness.current(7)
                        : Freshness.current(8);

        assertThat(service(query).executeAvailable()).isEqualTo(1);
        assertThat(taskState(task)).isEqualTo("SUPERSEDED");
        assertThat(providerCalls).hasValue(0);
        assertThat(reads).hasValue(2);
    }

    @Test
    void rateLimitBecomesRetryableAttemptWithoutSecretLeakage() {
        server.createContext("/scim/v2/Users", exchange -> {
            exchange.getResponseHeaders().set("Retry-After", "17");
            respond(exchange, 429, """
                    {
                      "status":"429",
                      "scimType":"tooMany",
                      "detail":"do not persist local-test-token"
                    }
                    """);
        });
        server.start();

        TenantContext tenant = tenant("rate-limit");
        UUID binding = connector(tenant, true, 100);
        UUID subject = ids.nextId();
        freshness.put(subject, Freshness.current(5));
        UUID task = provisioning(
                tenant,
                binding,
                subject,
                5,
                "UPSERT_PRINCIPAL",
                Map.of("userName", "rate-limited"));

        assertThat(service().executeAvailable()).isEqualTo(1);
        assertThat(taskState(task)).isEqualTo("FAILED_RETRYABLE");
        assertThat(jdbc.queryForObject("""
                SELECT failure_category
                FROM integration.provisioning_attempt
                WHERE task_id = ?
                """, String.class, task)).isEqualTo("RATE_LIMITED");
        assertThat(jdbc.queryForObject("""
                SELECT provider_error_code
                FROM integration.provisioning_attempt
                WHERE task_id = ?
                """, String.class, task)).isEqualTo("tooMany");
        assertThat(jdbc.queryForObject("""
                SELECT next_attempt_at IS NOT NULL
                FROM integration.provisioning_task
                WHERE id = ?
                """, Boolean.class, task)).isTrue();

        String persisted = jdbc.queryForObject("""
                SELECT concat_ws(' ',
                    COALESCE(a.provider_error_code, ''),
                    COALESCE(a.provider_request_id, ''),
                    COALESCE(a.metadata::text, ''),
                    COALESCE(t.failure_code, ''),
                    COALESCE(t.payload::text, ''))
                FROM integration.provisioning_attempt a
                JOIN integration.provisioning_task t
                  ON t.tenant_id = a.tenant_id AND t.id = a.task_id
                WHERE a.task_id = ?
                """, String.class, task);
        assertThat(persisted).doesNotContain("local-test-token");
        assertThat(persisted).doesNotContain("do not persist");
    }

    @Test
    void reconciliationMaterializesCompleteAndPartialCoverageWithoutDestructivePartialAbsence() {
        AtomicReference<String> response = new AtomicReference<>("""
                {
                  "totalResults":2,
                  "startIndex":1,
                  "itemsPerPage":2,
                  "Resources":[
                    {"id":"p-a","userName":"alice","meta":{"version":"v1"}},
                    {"id":"p-b","userName":"bob","meta":{"version":"v1"}}
                  ]
                }
                """);
        server.createContext("/scim/v2/Users", exchange ->
                respond(exchange, 200, response.get()));
        server.start();

        TenantContext tenant = tenant("local-reconcile");
        UUID binding = connector(tenant, true, 100);

        UUID initialRun = reconciliation(tenant, binding);
        assertThat(service().executeAvailable()).isEqualTo(1);
        assertThat(runState(initialRun)).isEqualTo("SUCCEEDED");
        assertThat(effectiveCompleteness(initialRun)).isEqualTo("COMPLETE");
        assertThat(present(tenant, binding, "p-a")).isTrue();
        assertThat(present(tenant, binding, "p-b")).isTrue();

        setMaxPages(tenant, binding, 1);
        response.set("""
                {
                  "totalResults":2,
                  "startIndex":1,
                  "itemsPerPage":1,
                  "Resources":[
                    {"id":"p-a","userName":"alice-2","meta":{"version":"v2"}}
                  ]
                }
                """);
        UUID partialRun = reconciliation(tenant, binding);
        assertThat(service().executeAvailable()).isEqualTo(1);
        assertThat(effectiveCompleteness(partialRun)).isEqualTo("PARTIAL");
        assertThat(present(tenant, binding, "p-b")).isTrue();

        setMaxPages(tenant, binding, 100);
        response.set("""
                {
                  "totalResults":1,
                  "startIndex":1,
                  "itemsPerPage":1,
                  "Resources":[
                    {"id":"p-a","userName":"alice-3","meta":{"version":"v3"}}
                  ]
                }
                """);
        UUID completeRun = reconciliation(tenant, binding);
        assertThat(service().executeAvailable()).isEqualTo(1);
        assertThat(effectiveCompleteness(completeRun)).isEqualTo("COMPLETE");
        assertThat(present(tenant, binding, "p-a")).isTrue();
        assertThat(present(tenant, binding, "p-b")).isFalse();
    }

    private ScimLocalExecutionService service() {
        DesiredAccessStateQuery query = (tenant, kind, subjectId) ->
                freshness.getOrDefault(subjectId, Freshness.unavailable());
        return service(query);
    }

    private ScimLocalExecutionService service(DesiredAccessStateQuery query) {
        var adapter = new ScimPrincipalProviderAdapter(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(2))
                        .build(),
                json,
                secretReference -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isFalse();
                    assertThat(secretReference).isEqualTo("test-ref");
                    return "local-test-token".toCharArray();
                });
        return new ScimLocalExecutionService(
                repository,
                repository,
                query,
                transactions,
                adapter,
                new ScimLocalExecutionProperties(
                        true, Duration.ofSeconds(1), Duration.ofSeconds(30), 10),
                json);
    }

    private TenantContext tenant(String name) {
        return new TenantContext(tenants.create(name, NOW).id());
    }

    private UUID connector(
            TenantContext tenant,
            boolean completeDiscovery,
            int maxPages) {
        UUID connectorId = ids.nextId();
        UUID bindingId = ids.nextId();
        Map<String,Object> configuration = Map.of(
                "baseUri", baseUri.toString(),
                "pageSize", 100,
                "maxPagesPerExecution", maxPages,
                "requestTimeoutSeconds", 2,
                "idempotencyHeader", "Idempotency-Key");
        jdbc.update("""
                INSERT INTO integration.connector_instance (
                    id, tenant_id, connector_type, runtime_id, runtime_version,
                    configuration_version, configuration_json, secret_reference,
                    lifecycle_state, revision, created_at, updated_at)
                VALUES (?, ?, 'SCIM_2', 'scim-2', '1.0',
                        1, ?::jsonb, 'test-ref', 'ACTIVE', 1, ?, ?)
                """,
                connectorId, tenant.tenantId(), writeJson(configuration),
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO integration.connector_binding (
                    id, tenant_id, connector_instance_id, target_kind, target_id,
                    contract_id, contract_version,
                    supports_complete_principal_discovery,
                    lifecycle_state, revision, created_at, updated_at)
                VALUES (?, ?, ?, 'APPLICATION_TARGET', ?,
                        'scim-2.principal', 1, ?, 'ACTIVE', 1, ?, ?)
                """,
                bindingId, tenant.tenantId(), connectorId, ids.nextId(),
                completeDiscovery, Timestamp.from(NOW), Timestamp.from(NOW));
        return bindingId;
    }

    private UUID provisioning(
            TenantContext tenant,
            UUID bindingId,
            UUID subjectId,
            long desiredRevision,
            String operationType,
            Map<String,Object> payload) {
        UUID jobId = ids.nextId();
        UUID taskId = ids.nextId();
        UUID correlationId = ids.nextId();
        jdbc.update("""
                INSERT INTO integration.provisioning_job (
                    id, tenant_id, connector_binding_id, state, revision,
                    correlation_id, created_at, updated_at)
                VALUES (?, ?, ?, 'RUNNING', 1, ?, ?, ?)
                """,
                jobId, tenant.tenantId(), bindingId, correlationId,
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO integration.provisioning_task (
                    id, tenant_id, provisioning_job_id, operation_id, operation_type,
                    subject_kind, subject_id, desired_revision, idempotency_key,
                    contract_id, contract_version, payload, state, attempt_count,
                    revision, correlation_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'DESIRED_PRINCIPAL', ?, ?, ?,
                        'scim-2.principal', 1, ?::jsonb, 'READY', 0,
                        1, ?, ?, ?)
                """,
                taskId, tenant.tenantId(), jobId, ids.nextId(), operationType,
                subjectId, desiredRevision, "local-" + taskId,
                writeJson(payload), correlationId,
                Timestamp.from(NOW), Timestamp.from(NOW));
        return taskId;
    }

    private UUID reconciliation(TenantContext tenant, UUID bindingId) {
        UUID runId = ids.nextId();
        jdbc.update("""
                INSERT INTO integration.reconciliation_run (
                    id, tenant_id, connector_binding_id, operation_id,
                    scope_object_class, state, reported_coverage,
                    effective_completeness, configuration_version,
                    runtime_id, runtime_version, contract_id, contract_version,
                    correlation_id, revision, started_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'PRINCIPAL', 'RUNNING', 'UNKNOWN',
                        'UNKNOWN', 1, 'scim-2', '1.0',
                        'scim-2.principal', 1, ?, 1, ?, ?, ?)
                """,
                runId, tenant.tenantId(), bindingId, ids.nextId(), ids.nextId(),
                Timestamp.from(NOW), Timestamp.from(NOW), Timestamp.from(NOW));
        return runId;
    }

    private void setMaxPages(TenantContext tenant, UUID bindingId, int maxPages) {
        UUID connectorId = jdbc.queryForObject("""
                SELECT connector_instance_id
                FROM integration.connector_binding
                WHERE tenant_id = ? AND id = ?
                """, UUID.class, tenant.tenantId(), bindingId);
        Map<String,Object> configuration = Map.of(
                "baseUri", baseUri.toString(),
                "pageSize", 100,
                "maxPagesPerExecution", maxPages,
                "requestTimeoutSeconds", 2,
                "idempotencyHeader", "Idempotency-Key");
        jdbc.update("""
                UPDATE integration.connector_instance
                SET configuration_json = ?::jsonb
                WHERE tenant_id = ? AND id = ?
                """, writeJson(configuration), tenant.tenantId(), connectorId);
    }

    private String taskState(UUID taskId) {
        return jdbc.queryForObject(
                "SELECT state FROM integration.provisioning_task WHERE id = ?",
                String.class, taskId);
    }

    private int leaseCount(UUID workId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM platform.connector_work_lease WHERE work_id = ?",
                Integer.class, workId);
        return count == null ? 0 : count;
    }

    private String runState(UUID runId) {
        return jdbc.queryForObject(
                "SELECT state FROM integration.reconciliation_run WHERE id = ?",
                String.class, runId);
    }

    private String effectiveCompleteness(UUID runId) {
        return jdbc.queryForObject(
                "SELECT effective_completeness FROM integration.reconciliation_run WHERE id = ?",
                String.class, runId);
    }

    private boolean present(
            TenantContext tenant,
            UUID bindingId,
            String providerStableId) {
        Boolean value = jdbc.queryForObject("""
                SELECT present
                FROM integration.observed_principal
                WHERE tenant_id = ?
                  AND connector_binding_id = ?
                  AND provider_stable_id = ?
                """, Boolean.class, tenant.tenantId(), bindingId, providerStableId);
        return Boolean.TRUE.equals(value);
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception invalid) {
            throw new IllegalArgumentException(invalid);
        }
    }

    private void clearDatabase() {
        jdbc.execute("""
                TRUNCATE TABLE
                    platform.connector_work_lease,
                    integration.observed_principal,
                    integration.reconciliation_principal_staging,
                    integration.reconciliation_observation_batch,
                    integration.reconciliation_run,
                    integration.provisioning_attempt,
                    integration.provisioning_task_dependency,
                    integration.provisioning_task,
                    integration.provisioning_job,
                    integration.connector_worker_session_contract,
                    integration.connector_worker_session_capability,
                    integration.connector_worker_session,
                    integration.connector_worker_runtime_permission,
                    integration.connector_worker_binding_scope,
                    integration.connector_worker_registration,
                    integration.connector_binding,
                    integration.connector_instance,
                    platform.scheduled_work,
                    platform.idempotency_record,
                    platform.inbox_message,
                    platform.outbox_event,
                    platform.tenant
                CASCADE
                """);
    }

    private static void respond(
            HttpExchange exchange,
            int status,
            String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/scim+json");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
