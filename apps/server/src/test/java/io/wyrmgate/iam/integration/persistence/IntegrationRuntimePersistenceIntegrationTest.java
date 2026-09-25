package io.wyrmgate.iam.integration.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.integration.application.ConnectorWorkRepository;
import io.wyrmgate.iam.integration.application.ConnectorWorkerProtocolProperties;
import io.wyrmgate.iam.integration.application.ConnectorWorkerProtocolService;
import io.wyrmgate.iam.access.application.DesiredAccessStateQuery;
import io.wyrmgate.iam.access.application.DesiredAccessStateQuery.Freshness;
import io.wyrmgate.iam.integration.application.WorkerProtocolException;
import io.wyrmgate.iam.integration.domain.ReconciliationCompleteness;
import io.wyrmgate.iam.integration.domain.WorkerCapability;
import io.wyrmgate.iam.integration.domain.WorkerExternalSubject;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.JdbcTenantRepository;
import io.wyrmgate.iam.platform.persistence.SpringTransactionExecutor;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class IntegrationRuntimePersistenceIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");
    private static final Instant NOW = Instant.parse("2026-09-21T06:00:00Z");
    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static JdbcIntegrationRuntimeRepository repository;
    private static TransactionExecutor transactions;
    private static ObjectMapper json;

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
        var outbox = new JdbcOutboxRepository(jdbc);
        var observedAccessFacts = new JdbcIntegrationObservedAccessFactSink(outbox, ids);
        repository = new JdbcIntegrationRuntimeRepository(
                jdbc, json, ids, observedAccessFacts);
        transactions = new SpringTransactionExecutor(new DataSourceTransactionManager(dataSource));
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("18");
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @BeforeEach
    void clearDatabase() {
        jdbc.execute("""
                TRUNCATE TABLE
                    platform.connector_work_lease,
                    integration.observed_grant,
                    integration.observed_entitlement,
                    integration.observed_principal,
                    integration.reconciliation_grant_staging,
                    integration.reconciliation_entitlement_staging,
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

    @Test
    void workerNegotiationIsServerScopedAndCrossTenantWorkIsNotClaimed() {
        TenantContext owner = tenant("owner");
        TenantContext other = tenant("other");
        UUID ownerBinding = connector(owner, "runtime.test", "1.0", true);
        UUID otherBinding = connector(other, "runtime.test", "1.0", true);
        WorkerExternalSubject subject = new WorkerExternalSubject("https://issuer.example", "worker-1");
        UUID workerId = worker(owner, subject, ownerBinding, WorkerCapability.RECONCILE);

        var service = service(revisionUnavailable());
        var registration = repository.findEnabledByExternalSubject(subject).orElseThrow();
        assertThat(registration.id()).isEqualTo(workerId);

        var session = service.establishSession(
                registration,
                "pod-a",
                List.of(1),
                List.of(runtimeAdvertisement("runtime.test", "1.0", WorkerCapability.RECONCILE)));

        assertThat(session.runtimes()).hasSize(1);
        assertThat(session.runtimes().getFirst().capabilities())
                .containsExactly(WorkerCapability.RECONCILE);

        createReconciliation(owner, ownerBinding, "runtime.test", "1.0", 1);
        createReconciliation(other, otherBinding, "runtime.test", "1.0", 1);

        var claimed = service.claim(registration, session.id(), 10, 0);
        assertThat(claimed).hasSize(1);
        assertThat(claimed.getFirst().tenant()).isEqualTo(owner);

        assertThatThrownBy(() -> service.establishSession(
                        registration,
                        "pod-b",
                        List.of(2),
                        List.of(runtimeAdvertisement("runtime.test", "1.0", WorkerCapability.RECONCILE))))
                .isInstanceOf(WorkerProtocolException.class)
                .hasMessageContaining("common protocol");
    }

    @Test
    void expiredLeaseIsReclaimedWithHigherEpochAndOldGenerationIsFenced() {
        TenantContext tenant = tenant("lease");
        UUID binding = connector(tenant, "runtime.test", "1.0", true);
        WorkerExternalSubject subject = new WorkerExternalSubject("https://issuer.example", "lease-worker");
        worker(tenant, subject, binding, WorkerCapability.RECONCILE);
        var service = service(revisionUnavailable());
        var registration = repository.findEnabledByExternalSubject(subject).orElseThrow();
        var session = service.establishSession(
                registration, "pod-a", List.of(1),
                List.of(runtimeAdvertisement("runtime.test", "1.0", WorkerCapability.RECONCILE)));
        UUID runId = createReconciliation(tenant, binding, "runtime.test", "1.0", 1);

        var first = service.claim(registration, session.id(), 1, 0).getFirst();
        assertThat(first.lease().leaseEpoch()).isEqualTo(1);

        jdbc.update("""
                UPDATE platform.connector_work_lease
                SET claimed_at = ?, lease_expires_at = ?, updated_at = ?
                WHERE tenant_id = ? AND work_id = ?
                """,
                Timestamp.from(NOW),
                Timestamp.from(NOW.plusSeconds(1)),
                Timestamp.from(NOW.plusSeconds(1)),
                tenant.tenantId(),
                runId);

        var second = service.claim(registration, session.id(), 1, 0).getFirst();
        assertThat(second.lease().leaseEpoch()).isEqualTo(2);

        assertThatThrownBy(() -> service.complete(
                        registration, session.id(), runId,
                        first.lease().leaseId(), first.lease().leaseEpoch(),
                        success(ReconciliationCompleteness.PARTIAL)))
                .isInstanceOf(WorkerProtocolException.class)
                .hasMessageContaining("stale");

        assertThat(service.complete(
                registration, session.id(), runId,
                second.lease().leaseId(), second.lease().leaseEpoch(),
                success(ReconciliationCompleteness.PARTIAL)))
                .isEqualTo(ConnectorWorkRepository.CompletionResult.ACCEPTED);
    }

    @Test
    void partialRunNeverMarksUnseenPrincipalAbsentButTrustedCompleteRunDoes() {
        TenantContext tenant = tenant("coverage");
        UUID binding = connector(tenant, "runtime.test", "1.0", true);
        WorkerExternalSubject subject = new WorkerExternalSubject("https://issuer.example", "coverage-worker");
        worker(tenant, subject, binding, WorkerCapability.RECONCILE);
        var service = service(revisionUnavailable());
        var registration = repository.findEnabledByExternalSubject(subject).orElseThrow();
        var session = service.establishSession(
                registration, "pod-a", List.of(1),
                List.of(runtimeAdvertisement("runtime.test", "1.0", WorkerCapability.RECONCILE)));

        UUID firstRun = createReconciliation(tenant, binding, "runtime.test", "1.0", 1);
        var firstLease = service.claim(registration, session.id(), 1, 0).getFirst();
        append(service, registration, session.id(), firstRun, firstLease.lease().leaseId(),
                firstLease.lease().leaseEpoch(), List.of(
                        observation("p-a", "A"),
                        observation("p-b", "B")));
        service.complete(
                registration, session.id(), firstRun,
                firstLease.lease().leaseId(), firstLease.lease().leaseEpoch(),
                success(ReconciliationCompleteness.COMPLETE));
        assertThat(present(tenant, binding, "p-a")).isTrue();
        assertThat(present(tenant, binding, "p-b")).isTrue();

        UUID partialRun = createReconciliation(tenant, binding, "runtime.test", "1.0", 1);
        var partialLease = service.claim(registration, session.id(), 1, 0).getFirst();
        append(service, registration, session.id(), partialRun, partialLease.lease().leaseId(),
                partialLease.lease().leaseEpoch(), List.of(observation("p-a", "A2")));
        service.complete(
                registration, session.id(), partialRun,
                partialLease.lease().leaseId(), partialLease.lease().leaseEpoch(),
                success(ReconciliationCompleteness.PARTIAL));
        assertThat(present(tenant, binding, "p-b")).isTrue();
        assertThat(effectiveCompleteness(partialRun)).isEqualTo("PARTIAL");

        UUID completeRun = createReconciliation(tenant, binding, "runtime.test", "1.0", 1);
        var completeLease = service.claim(registration, session.id(), 1, 0).getFirst();
        append(service, registration, session.id(), completeRun, completeLease.lease().leaseId(),
                completeLease.lease().leaseEpoch(), List.of(observation("p-a", "A3")));
        service.complete(
                registration, session.id(), completeRun,
                completeLease.lease().leaseId(), completeLease.lease().leaseEpoch(),
                success(ReconciliationCompleteness.COMPLETE));

        assertThat(present(tenant, binding, "p-a")).isTrue();
        assertThat(present(tenant, binding, "p-b")).isFalse();
        assertThat(effectiveCompleteness(completeRun)).isEqualTo("COMPLETE");
    }

    @Test
    void observationAndCompletionReplayAreIdempotentButConflictsFail() {
        TenantContext tenant = tenant("replay");
        UUID binding = connector(tenant, "runtime.test", "1.0", true);
        WorkerExternalSubject subject = new WorkerExternalSubject("https://issuer.example", "replay-worker");
        worker(tenant, subject, binding, WorkerCapability.RECONCILE);
        var service = service(revisionUnavailable());
        var registration = repository.findEnabledByExternalSubject(subject).orElseThrow();
        var session = service.establishSession(
                registration, "pod-a", List.of(1),
                List.of(runtimeAdvertisement("runtime.test", "1.0", WorkerCapability.RECONCILE)));
        UUID runId = createReconciliation(tenant, binding, "runtime.test", "1.0", 1);
        var lease = service.claim(registration, session.id(), 1, 0).getFirst();
        UUID batchId = ids.nextId();
        var observations = List.of(observation("p-a", "A"));

        assertThat(service.appendObservations(
                registration, session.id(), runId, lease.lease().leaseId(),
                lease.lease().leaseEpoch(), batchId, 0, observations))
                .isEqualTo(ConnectorWorkRepository.ObservationBatchResult.ACCEPTED);
        assertThat(service.appendObservations(
                registration, session.id(), runId, lease.lease().leaseId(),
                lease.lease().leaseEpoch(), batchId, 0, observations))
                .isEqualTo(ConnectorWorkRepository.ObservationBatchResult.REPLAY);

        assertThatThrownBy(() -> service.appendObservations(
                        registration, session.id(), runId, lease.lease().leaseId(),
                        lease.lease().leaseEpoch(), batchId, 0,
                        List.of(observation("p-b", "B"))))
                .isInstanceOf(WorkerProtocolException.class)
                .hasMessageContaining("different content");

        var completion = success(ReconciliationCompleteness.PARTIAL);
        assertThat(service.complete(
                registration, session.id(), runId,
                lease.lease().leaseId(), lease.lease().leaseEpoch(), completion))
                .isEqualTo(ConnectorWorkRepository.CompletionResult.ACCEPTED);
        assertThat(service.complete(
                registration, session.id(), runId,
                lease.lease().leaseId(), lease.lease().leaseEpoch(), completion))
                .isEqualTo(ConnectorWorkRepository.CompletionResult.REPLAY);
    }

    @Test
    void provisioningStaleRevisionSupersedesAndFreshCompletionCreatesImmutableAttempt() {
        TenantContext tenant = tenant("provision");
        UUID binding = connector(tenant, "runtime.test", "1.0", true);
        WorkerExternalSubject subject = new WorkerExternalSubject("https://issuer.example", "provision-worker");
        worker(tenant, subject, binding, WorkerCapability.PROVISION);
        var registration = repository.findEnabledByExternalSubject(subject).orElseThrow();

        UUID staleSubject = ids.nextId();
        UUID staleTask = createProvisioning(tenant, binding, staleSubject, 2);
        var staleService = service((t, kind, id) -> Freshness.current(3));
        var staleSession = staleService.establishSession(
                registration, "pod-stale", List.of(1),
                List.of(runtimeAdvertisement("runtime.test", "1.0", WorkerCapability.PROVISION)));
        assertThat(staleService.claim(registration, staleSession.id(), 1, 0)).isEmpty();
        assertThat(taskState(staleTask)).isEqualTo("SUPERSEDED");

        UUID freshSubject = ids.nextId();
        UUID freshTask = createProvisioning(tenant, binding, freshSubject, 7);
        var freshService = service((t, kind, id) -> Freshness.current(7));
        var freshSession = freshService.establishSession(
                registration, "pod-fresh", List.of(1),
                List.of(runtimeAdvertisement("runtime.test", "1.0", WorkerCapability.PROVISION)));
        var lease = freshService.claim(registration, freshSession.id(), 1, 0).getFirst();
        assertThat(lease.workId()).isEqualTo(freshTask);

        var completion = new ConnectorWorkRepository.WorkCompletion(
                "SUCCEEDED", null, null, "provider-request-1", null,
                "provider-object-1", "v1", Map.of("changed", true), null, null);
        freshService.complete(
                registration, freshSession.id(), freshTask,
                lease.lease().leaseId(), lease.lease().leaseEpoch(), completion);
        assertThat(taskState(freshTask)).isEqualTo("SUCCEEDED");
        assertThat(attemptCount(freshTask)).isEqualTo(1);

        assertThatThrownBy(() -> jdbc.update("""
                        UPDATE integration.provisioning_attempt
                        SET provider_request_id = 'mutated'
                        WHERE tenant_id = ? AND task_id = ?
                        """, tenant.tenantId(), freshTask))
                .isInstanceOf(org.springframework.jdbc.UncategorizedSQLException.class)
                .hasMessageContaining("provisioning_attempt is immutable");
    }

    @Test
    void provisioningAbsentDesiredStateSupersedesWhileUnavailableVerificationLeavesWorkUnclaimed() {
        TenantContext tenant = tenant("freshness");
        UUID binding = connector(tenant, "runtime.test", "1.0", true);
        WorkerExternalSubject subject = new WorkerExternalSubject("https://issuer.example", "freshness-worker");
        worker(tenant, subject, binding, WorkerCapability.PROVISION);
        var registration = repository.findEnabledByExternalSubject(subject).orElseThrow();

        UUID absentTask = createProvisioning(
                tenant, binding, "DESIRED_PRINCIPAL", ids.nextId(), 4);
        var absentService = service((t, kind, id) -> Freshness.absent());
        var absentSession = absentService.establishSession(
                registration, "pod-absent", List.of(1),
                List.of(runtimeAdvertisement("runtime.test", "1.0", WorkerCapability.PROVISION)));

        assertThat(absentService.claim(registration, absentSession.id(), 1, 0)).isEmpty();
        assertThat(taskState(absentTask)).isEqualTo("SUPERSEDED");

        UUID unavailableTask = createProvisioning(
                tenant, binding, "DESIRED_GRANT", ids.nextId(), 9);
        var unavailableService = service((t, kind, id) -> Freshness.unavailable());
        var unavailableSession = unavailableService.establishSession(
                registration, "pod-unavailable", List.of(1),
                List.of(runtimeAdvertisement("runtime.test", "1.0", WorkerCapability.PROVISION)));

        assertThat(unavailableService.claim(registration, unavailableSession.id(), 1, 0)).isEmpty();
        assertThat(taskState(unavailableTask)).isEqualTo("READY");
        assertThat(leaseCount(tenant, unavailableTask)).isZero();
    }

    @Test
    void desiredGrantCurrentRevisionCanBeClaimed() {
        TenantContext tenant = tenant("grant");
        UUID binding = connector(tenant, "runtime.test", "1.0", true);
        WorkerExternalSubject subject = new WorkerExternalSubject("https://issuer.example", "grant-worker");
        worker(tenant, subject, binding, WorkerCapability.PROVISION);
        var registration = repository.findEnabledByExternalSubject(subject).orElseThrow();

        UUID desiredGrantId = ids.nextId();
        UUID taskId = createProvisioning(
                tenant, binding, "DESIRED_GRANT", desiredGrantId, 6);
        var service = service((t, kind, id) -> {
            assertThat(kind).isEqualTo(DesiredAccessStateQuery.SubjectKind.DESIRED_GRANT);
            assertThat(id).isEqualTo(desiredGrantId);
            return Freshness.current(6);
        });
        var session = service.establishSession(
                registration, "pod-grant", List.of(1),
                List.of(runtimeAdvertisement("runtime.test", "1.0", WorkerCapability.PROVISION)));

        var claimed = service.claim(registration, session.id(), 1, 0);
        assertThat(claimed).hasSize(1);
        assertThat(claimed.getFirst().workId()).isEqualTo(taskId);
        assertThat(claimed.getFirst().desiredRevision()).isEqualTo(6);
    }

    @Test
    void entitlementAndGrantCoverageAreIndependentAndPartialNeverInfersAbsence() {
        TenantContext tenant = tenant("group-observations");
        UUID binding = groupConnector(tenant, true, false);
        WorkerExternalSubject subject =
                new WorkerExternalSubject("https://issuer.example", "group-worker");
        groupWorker(tenant, subject, binding, WorkerCapability.RECONCILE);

        var service = service(revisionUnavailable());
        var registration = repository.findEnabledByExternalSubject(subject).orElseThrow();
        var session = service.establishSession(
                registration,
                "pod-groups",
                List.of(1),
                List.of(groupRuntimeAdvertisement(WorkerCapability.RECONCILE)));

        UUID firstEntitlementRun = createGroupReconciliation(
                tenant, binding, "ENTITLEMENT");
        var firstLease = service.claim(registration, session.id(), 1, 0).getFirst();
        append(service, registration, session.id(), firstEntitlementRun,
                firstLease.lease().leaseId(), firstLease.lease().leaseEpoch(),
                List.of(
                        new ConnectorWorkRepository.ProviderObservation(
                                "ENTITLEMENT", "g-a", "v1", Map.of("displayName", "A")),
                        new ConnectorWorkRepository.ProviderObservation(
                                "ENTITLEMENT", "g-b", "v1", Map.of("displayName", "B"))));
        service.complete(
                registration, session.id(), firstEntitlementRun,
                firstLease.lease().leaseId(), firstLease.lease().leaseEpoch(),
                success(ReconciliationCompleteness.COMPLETE));

        assertThat(observedAccessTriggerCount(tenant, firstEntitlementRun)).isEqualTo(1);
        assertThat(observationPresent(
                "observed_entitlement", tenant, binding, "g-a")).isTrue();
        assertThat(observationPresent(
                "observed_entitlement", tenant, binding, "g-b")).isTrue();
        assertThat(effectiveCompleteness(firstEntitlementRun)).isEqualTo("COMPLETE");

        UUID partialEntitlementRun = createGroupReconciliation(
                tenant, binding, "ENTITLEMENT");
        var partialLease = service.claim(registration, session.id(), 1, 0).getFirst();
        append(service, registration, session.id(), partialEntitlementRun,
                partialLease.lease().leaseId(), partialLease.lease().leaseEpoch(),
                List.of(new ConnectorWorkRepository.ProviderObservation(
                        "ENTITLEMENT", "g-a", "v2", Map.of("displayName", "A2"))));
        service.complete(
                registration, session.id(), partialEntitlementRun,
                partialLease.lease().leaseId(), partialLease.lease().leaseEpoch(),
                success(ReconciliationCompleteness.PARTIAL));
        assertThat(observationPresent(
                "observed_entitlement", tenant, binding, "g-b")).isTrue();

        UUID completeEntitlementRun = createGroupReconciliation(
                tenant, binding, "ENTITLEMENT");
        var completeLease = service.claim(registration, session.id(), 1, 0).getFirst();
        append(service, registration, session.id(), completeEntitlementRun,
                completeLease.lease().leaseId(), completeLease.lease().leaseEpoch(),
                List.of(new ConnectorWorkRepository.ProviderObservation(
                        "ENTITLEMENT", "g-a", "v3", Map.of("displayName", "A3"))));
        service.complete(
                registration, session.id(), completeEntitlementRun,
                completeLease.lease().leaseId(), completeLease.lease().leaseEpoch(),
                success(ReconciliationCompleteness.COMPLETE));
        assertThat(observationPresent(
                "observed_entitlement", tenant, binding, "g-b")).isFalse();

        UUID grantRun = createGroupReconciliation(tenant, binding, "GRANT");
        var grantLease = service.claim(registration, session.id(), 1, 0).getFirst();
        append(service, registration, session.id(), grantRun,
                grantLease.lease().leaseId(), grantLease.lease().leaseEpoch(),
                List.of(new ConnectorWorkRepository.ProviderObservation(
                        "GRANT",
                        "grant-a",
                        "v3",
                        Map.of(
                                "principalProviderId", "u-a",
                                "entitlementProviderId", "g-a"))));
        service.complete(
                registration, session.id(), grantRun,
                grantLease.lease().leaseId(), grantLease.lease().leaseEpoch(),
                success(ReconciliationCompleteness.COMPLETE));

        assertThat(observedAccessTriggerCount(tenant, grantRun)).isEqualTo(1);
        assertThat(observationPresent(
                "observed_grant", tenant, binding, "grant-a")).isTrue();
        assertThat(effectiveCompleteness(grantRun)).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("""
                SELECT principal_provider_id
                FROM integration.observed_grant
                WHERE tenant_id = ? AND connector_binding_id = ?
                  AND provider_stable_id = 'grant-a'
                """, String.class, tenant.tenantId(), binding)).isEqualTo("u-a");
    }

    @Test
    void secretShapedWorkerMetadataIsRejectedBeforePersistence() {
        TenantContext tenant = tenant("secret");
        UUID binding = connector(tenant, "runtime.test", "1.0", true);
        WorkerExternalSubject subject = new WorkerExternalSubject("https://issuer.example", "secret-worker");
        worker(tenant, subject, binding, WorkerCapability.RECONCILE);
        var service = service(revisionUnavailable());
        var registration = repository.findEnabledByExternalSubject(subject).orElseThrow();
        var session = service.establishSession(
                registration, "pod-a", List.of(1),
                List.of(runtimeAdvertisement("runtime.test", "1.0", WorkerCapability.RECONCILE)));
        UUID runId = createReconciliation(tenant, binding, "runtime.test", "1.0", 1);
        var lease = service.claim(registration, session.id(), 1, 0).getFirst();

        assertThatThrownBy(() -> service.appendObservations(
                        registration, session.id(), runId,
                        lease.lease().leaseId(), lease.lease().leaseEpoch(),
                        ids.nextId(), 0,
                        List.of(new ConnectorWorkRepository.ProviderObservation(
                                "PRINCIPAL", "p-a", null,
                                Map.of("client_secret", "must-not-pass")))))
                .isInstanceOf(WorkerProtocolException.class)
                .hasMessageContaining("forbidden secret-shaped");
        assertThat(stagingCount(runId)).isZero();
    }

    private UUID groupConnector(
            TenantContext tenant,
            boolean completeEntitlements,
            boolean completeGrants) {
        UUID instanceId = ids.nextId();
        UUID bindingId = ids.nextId();
        jdbc.update("""
                INSERT INTO integration.connector_instance (
                    id, tenant_id, connector_type, runtime_id, runtime_version,
                    configuration_version, configuration_json, lifecycle_state,
                    revision, created_at, updated_at)
                VALUES (?, ?, 'TEST', 'runtime.test', '1.0',
                        1, '{}'::jsonb, 'ACTIVE', 1, ?, ?)
                """,
                instanceId, tenant.tenantId(),
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO integration.connector_binding (
                    id, tenant_id, connector_instance_id, target_kind, target_id,
                    contract_id, contract_version,
                    supports_complete_principal_discovery,
                    supports_complete_entitlement_discovery,
                    supports_complete_grant_discovery,
                    lifecycle_state, revision, created_at, updated_at)
                VALUES (?, ?, ?, 'APPLICATION_TARGET', ?,
                        'test.group', 1, false, ?, ?,
                        'ACTIVE', 1, ?, ?)
                """,
                bindingId, tenant.tenantId(), instanceId, ids.nextId(),
                completeEntitlements, completeGrants,
                Timestamp.from(NOW), Timestamp.from(NOW));
        return bindingId;
    }

    private UUID groupWorker(
            TenantContext tenant,
            WorkerExternalSubject subject,
            UUID bindingId,
            WorkerCapability capability) {
        UUID workerId = ids.nextId();
        jdbc.update("""
                INSERT INTO integration.connector_worker_registration (
                    id, tenant_id, external_subject_key, issuer, subject, state,
                    protocol_major_min, protocol_major_max,
                    revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'ENABLED', 1, 1, 1, ?, ?)
                """,
                workerId, tenant.tenantId(),
                JdbcIntegrationRuntimeRepository.subjectKey(subject),
                subject.issuer(), subject.subject(),
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO integration.connector_worker_binding_scope (
                    tenant_id, worker_registration_id,
                    connector_binding_id, created_at)
                VALUES (?, ?, ?, ?)
                """,
                tenant.tenantId(), workerId, bindingId, Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO integration.connector_worker_runtime_permission (
                    tenant_id, worker_registration_id,
                    runtime_id, runtime_version,
                    capability, contract_id, contract_version, created_at)
                VALUES (?, ?, 'runtime.test', '1.0', ?, 'test.group', 1, ?)
                """,
                tenant.tenantId(), workerId, capability.name(), Timestamp.from(NOW));
        return workerId;
    }

    private ConnectorWorkerProtocolService.RuntimeAdvertisement groupRuntimeAdvertisement(
            WorkerCapability capability) {
        return new ConnectorWorkerProtocolService.RuntimeAdvertisement(
                "runtime.test",
                "1.0",
                List.of(capability),
                List.of(new ConnectorWorkerProtocolService.ContractAdvertisement(
                        "test.group", List.of(1))));
    }

    private UUID createGroupReconciliation(
            TenantContext tenant,
            UUID bindingId,
            String objectClass) {
        UUID id = ids.nextId();
        jdbc.update("""
                INSERT INTO integration.reconciliation_run (
                    id, tenant_id, connector_binding_id, operation_id,
                    scope_object_class, state, reported_coverage,
                    effective_completeness, configuration_version,
                    runtime_id, runtime_version, contract_id, contract_version,
                    correlation_id, revision, started_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'RUNNING', 'UNKNOWN', 'UNKNOWN', 1,
                        'runtime.test', '1.0', 'test.group', 1,
                        ?, 1, ?, ?, ?)
                """,
                id, tenant.tenantId(), bindingId, ids.nextId(), objectClass,
                ids.nextId(),
                Timestamp.from(NOW), Timestamp.from(NOW), Timestamp.from(NOW));
        return id;
    }

    private boolean observationPresent(
            String table,
            TenantContext tenant,
            UUID binding,
            String providerStableId) {
        if (!List.of("observed_entitlement", "observed_grant").contains(table)) {
            throw new IllegalArgumentException("unsupported observation table");
        }
        Boolean value = jdbc.queryForObject(
                ("SELECT present FROM integration." + table
                        + " WHERE tenant_id = ?"
                        + " AND connector_binding_id = ?"
                        + " AND provider_stable_id = ?"),
                Boolean.class,
                tenant.tenantId(),
                binding,
                providerStableId);
        return Boolean.TRUE.equals(value);
    }

    private ConnectorWorkerProtocolService service(DesiredAccessStateQuery query) {
        return new ConnectorWorkerProtocolService(
                repository, repository, query, transactions,
                new ConnectorWorkerProtocolProperties(
                        Duration.ofMinutes(15), Duration.ofSeconds(30), 50, 0, 500),
                json);
    }

    private static DesiredAccessStateQuery revisionUnavailable() {
        return (tenant, subjectKind, subjectId) -> Freshness.unavailable();
    }

    private TenantContext tenant(String name) {
        return new TenantContext(tenants.create(name, NOW).id());
    }

    private UUID connector(
            TenantContext tenant, String runtimeId, String runtimeVersion, boolean completeDiscovery) {
        UUID instanceId = ids.nextId();
        UUID bindingId = ids.nextId();
        jdbc.update("""
                INSERT INTO integration.connector_instance (
                    id, tenant_id, connector_type, runtime_id, runtime_version,
                    configuration_version, configuration_json, lifecycle_state,
                    revision, created_at, updated_at)
                VALUES (?, ?, 'TEST', ?, ?, 1, '{}'::jsonb, 'ACTIVE', 1, ?, ?)
                """,
                instanceId, tenant.tenantId(), runtimeId, runtimeVersion,
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO integration.connector_binding (
                    id, tenant_id, connector_instance_id, target_kind, target_id,
                    contract_id, contract_version, supports_complete_principal_discovery,
                    lifecycle_state, revision, created_at, updated_at)
                VALUES (?, ?, ?, 'APPLICATION_TARGET', ?, 'test.principal', 1, ?, 'ACTIVE', 1, ?, ?)
                """,
                bindingId, tenant.tenantId(), instanceId, ids.nextId(), completeDiscovery,
                Timestamp.from(NOW), Timestamp.from(NOW));
        return bindingId;
    }

    private UUID worker(
            TenantContext tenant,
            WorkerExternalSubject subject,
            UUID bindingId,
            WorkerCapability capability) {
        UUID workerId = ids.nextId();
        jdbc.update("""
                INSERT INTO integration.connector_worker_registration (
                    id, tenant_id, external_subject_key, issuer, subject, state,
                    protocol_major_min, protocol_major_max, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'ENABLED', 1, 1, 1, ?, ?)
                """,
                workerId, tenant.tenantId(),
                JdbcIntegrationRuntimeRepository.subjectKey(subject),
                subject.issuer(), subject.subject(),
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO integration.connector_worker_binding_scope (
                    tenant_id, worker_registration_id, connector_binding_id, created_at)
                VALUES (?, ?, ?, ?)
                """,
                tenant.tenantId(), workerId, bindingId, Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO integration.connector_worker_runtime_permission (
                    tenant_id, worker_registration_id, runtime_id, runtime_version,
                    capability, contract_id, contract_version, created_at)
                VALUES (?, ?, 'runtime.test', '1.0', ?, 'test.principal', 1, ?)
                """,
                tenant.tenantId(), workerId, capability.name(), Timestamp.from(NOW));
        return workerId;
    }

    private UUID createReconciliation(
            TenantContext tenant,
            UUID bindingId,
            String runtimeId,
            String runtimeVersion,
            long configurationVersion) {
        UUID id = ids.nextId();
        jdbc.update("""
                INSERT INTO integration.reconciliation_run (
                    id, tenant_id, connector_binding_id, operation_id, scope_object_class,
                    state, reported_coverage, effective_completeness, configuration_version,
                    runtime_id, runtime_version, contract_id, contract_version,
                    correlation_id, revision, started_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'PRINCIPAL', 'RUNNING', 'UNKNOWN', 'UNKNOWN', ?,
                        ?, ?, 'test.principal', 1, ?, 1, ?, ?, ?)
                """,
                id, tenant.tenantId(), bindingId, ids.nextId(), configurationVersion,
                runtimeId, runtimeVersion, ids.nextId(),
                Timestamp.from(NOW), Timestamp.from(NOW), Timestamp.from(NOW));
        return id;
    }

    private UUID createProvisioning(
            TenantContext tenant, UUID bindingId, UUID subjectId, long desiredRevision) {
        return createProvisioning(tenant, bindingId, "DESIRED_PRINCIPAL", subjectId, desiredRevision);
    }

    private UUID createProvisioning(
            TenantContext tenant,
            UUID bindingId,
            String subjectKind,
            UUID subjectId,
            long desiredRevision) {
        UUID jobId = ids.nextId();
        UUID taskId = ids.nextId();
        UUID correlation = ids.nextId();
        jdbc.update("""
                INSERT INTO integration.provisioning_job (
                    id, tenant_id, connector_binding_id, state, revision,
                    correlation_id, created_at, updated_at)
                VALUES (?, ?, ?, 'RUNNING', 1, ?, ?, ?)
                """,
                jobId, tenant.tenantId(), bindingId, correlation,
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO integration.provisioning_task (
                    id, tenant_id, provisioning_job_id, operation_id, operation_type,
                    subject_kind, subject_id, desired_revision, idempotency_key,
                    contract_id, contract_version, payload, state, attempt_count,
                    revision, correlation_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'UPSERT_PRINCIPAL', ?, ?, ?, ?,
                        'test.principal', 1, '{}'::jsonb, 'READY', 0, 1, ?, ?, ?)
                """,
                taskId, tenant.tenantId(), jobId, ids.nextId(), subjectKind, subjectId, desiredRevision,
                "provision-" + taskId, correlation, Timestamp.from(NOW), Timestamp.from(NOW));
        return taskId;
    }

    private ConnectorWorkerProtocolService.RuntimeAdvertisement runtimeAdvertisement(
            String runtimeId, String runtimeVersion, WorkerCapability capability) {
        return new ConnectorWorkerProtocolService.RuntimeAdvertisement(
                runtimeId, runtimeVersion, List.of(capability),
                List.of(new ConnectorWorkerProtocolService.ContractAdvertisement(
                        "test.principal", List.of(1))));
    }

    private ConnectorWorkRepository.ProviderObservation observation(String id, String display) {
        return new ConnectorWorkRepository.ProviderObservation(
                "PRINCIPAL", id, "v1", Map.of("displayName", display));
    }

    private void append(
            ConnectorWorkerProtocolService service,
            io.wyrmgate.iam.integration.domain.WorkerRegistration registration,
            UUID sessionId,
            UUID runId,
            UUID leaseId,
            long leaseEpoch,
            List<ConnectorWorkRepository.ProviderObservation> observations) {
        service.appendObservations(
                registration, sessionId, runId, leaseId, leaseEpoch,
                ids.nextId(), 0, observations);
    }

    private ConnectorWorkRepository.WorkCompletion success(ReconciliationCompleteness completeness) {
        return new ConnectorWorkRepository.WorkCompletion(
                "SUCCEEDED", null, null, null, null, null, null,
                Map.of(), completeness, "checkpoint-next");
    }

    private boolean present(TenantContext tenant, UUID binding, String providerStableId) {
        Boolean value = jdbc.queryForObject("""
                SELECT present FROM integration.observed_principal
                WHERE tenant_id = ? AND connector_binding_id = ? AND provider_stable_id = ?
                """, Boolean.class, tenant.tenantId(), binding, providerStableId);
        return Boolean.TRUE.equals(value);
    }

    private int observedAccessTriggerCount(
            TenantContext tenant, UUID sourceId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*)
                FROM platform.outbox_event
                WHERE tenant_id = ?
                  AND event_type = 'integration.observed-access-input-changed'
                  AND aggregate_type = 'reconciliation-run'
                  AND aggregate_id = ?
                """, Integer.class, tenant.tenantId(), sourceId);
        return count == null ? 0 : count;
    }

    private String effectiveCompleteness(UUID runId) {
        return jdbc.queryForObject("""
                SELECT effective_completeness FROM integration.reconciliation_run WHERE id = ?
                """, String.class, runId);
    }

    private String taskState(UUID taskId) {
        return jdbc.queryForObject(
                "SELECT state FROM integration.provisioning_task WHERE id = ?",
                String.class, taskId);
    }

    private int leaseCount(TenantContext tenant, UUID taskId) {
        Integer value = jdbc.queryForObject("""
                SELECT count(*) FROM platform.connector_work_lease
                WHERE tenant_id = ? AND work_kind = 'PROVISION' AND work_id = ?
                """, Integer.class, tenant.tenantId(), taskId);
        return value == null ? 0 : value;
    }

    private int attemptCount(UUID taskId) {
        Integer value = jdbc.queryForObject(
                "SELECT count(*) FROM integration.provisioning_attempt WHERE task_id = ?",
                Integer.class, taskId);
        return value == null ? 0 : value;
    }

    private int stagingCount(UUID runId) {
        Integer value = jdbc.queryForObject(
                "SELECT count(*) FROM integration.reconciliation_principal_staging WHERE reconciliation_run_id = ?",
                Integer.class, runId);
        return value == null ? 0 : value;
    }
}
