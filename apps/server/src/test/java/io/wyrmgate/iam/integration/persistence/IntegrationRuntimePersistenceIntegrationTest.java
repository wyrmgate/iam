package io.wyrmgate.iam.integration.persistence;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.integration.application.ConnectorWorkRepository;
import io.wyrmgate.iam.integration.application.ConnectorWorkerProtocolProperties;
import io.wyrmgate.iam.integration.application.ConnectorWorkerProtocolService;
import io.wyrmgate.iam.access.application.DesiredAccessStateQuery;
import io.wyrmgate.iam.access.application.DesiredProvisioningStateQuery;
import io.wyrmgate.iam.access.application.DesiredStateProjectionRepository.DesiredGrantState;
import io.wyrmgate.iam.access.application.DesiredStateProjectionRepository.DesiredPrincipalState;
import io.wyrmgate.iam.access.application.DesiredStateProjectionRepository.DesiredPresence;
import io.wyrmgate.iam.identity.application.PrincipalTechnicalReferenceQuery;
import io.wyrmgate.iam.integration.application.IntegrationAdministrationException;
import io.wyrmgate.iam.access.application.DesiredAccessStateQuery.Freshness;
import io.wyrmgate.iam.integration.application.WorkerProtocolException;
import io.wyrmgate.iam.integration.domain.ReconciliationCompleteness;
import io.wyrmgate.iam.integration.domain.WorkerCapability;
import io.wyrmgate.iam.integration.domain.WorkerExternalSubject;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.tenancy.persistence.TenantTestFixture;
import io.wyrmgate.iam.platform.persistence.SpringTransactionExecutor;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class IntegrationRuntimePersistenceIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-21T06:00:00Z");
    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static TenantTestFixture tenants;
    private static JdbcIntegrationRuntimeRepository repository;
    private static TransactionExecutor transactions;
    private static ObjectMapper json;

    @BeforeAll
    static void startPostgresAndMigrate() {
        io.wyrmgate.iam.platform.persistence.SharedPostgresIntegrationDatabase.reset();
        var dataSource = io.wyrmgate.iam.platform.persistence.SharedPostgresIntegrationDatabase.dataSource();
        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new TenantTestFixture(jdbc, ids);
        json = new ObjectMapper().findAndRegisterModules();
        var outbox = new JdbcOutboxRepository(jdbc);
        var observedAccessFacts = new JdbcIntegrationObservedAccessFactSink(outbox, ids, jdbc);
        repository = new JdbcIntegrationRuntimeRepository(
                jdbc, json, ids, observedAccessFacts);
        transactions = new SpringTransactionExecutor(new DataSourceTransactionManager(dataSource));
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
                    integration.provisioning_effect_recovery,
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
                    tenancy.tenant
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
    void largeReconciliationPersistsProgressAndFinalizesInBoundedTransactions() {
        TenantContext tenant=tenant("large-materialization");
        UUID binding=connector(tenant,"runtime.test","1.0",true);
        WorkerExternalSubject subject=new WorkerExternalSubject("https://issuer.example","large-worker");
        worker(tenant,subject,binding,WorkerCapability.RECONCILE);
        var service=service(revisionUnavailable());
        var registration=repository.findEnabledByExternalSubject(subject).orElseThrow();
        var session=service.establishSession(registration,"large-pod",List.of(1),
                List.of(runtimeAdvertisement("runtime.test","1.0",WorkerCapability.RECONCILE)));
        UUID run=createReconciliation(tenant,binding,"runtime.test","1.0",1);
        var lease=service.claim(registration,session.id(),1,0).getFirst();
        var observations=java.util.stream.IntStream.range(0,401)
                .mapToObj(n->observation("p-"+String.format("%04d",n),"P")).toList();
        append(service,registration,session.id(),run,lease.lease().leaseId(),lease.lease().leaseEpoch(),observations);
        var result=success(ReconciliationCompleteness.COMPLETE);
        assertThat(service.complete(registration,session.id(),run,lease.lease().leaseId(),lease.lease().leaseEpoch(),result))
                .isEqualTo(ConnectorWorkRepository.CompletionResult.ACCEPTED);
        assertThat(effectiveCompleteness(run)).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM integration.observed_principal WHERE tenant_id=?",Integer.class,tenant.tenantId())).isEqualTo(200);
        assertThat(service.claim(registration,session.id(),1,0)).isEmpty();
        assertThat(service.complete(registration,session.id(),run,lease.lease().leaseId(),lease.lease().leaseEpoch(),result))
                .isEqualTo(ConnectorWorkRepository.CompletionResult.REPLAY);
        assertThat(transactions.required(()->repository.materializeNext(Instant.now()))).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM integration.observed_principal WHERE tenant_id=?",Integer.class,tenant.tenantId())).isEqualTo(400);
        assertThat(transactions.required(()->repository.materializeNext(Instant.now()))).isTrue();
        assertThat(effectiveCompleteness(run)).isEqualTo("COMPLETE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM integration.observed_principal WHERE tenant_id=?",Integer.class,tenant.tenantId())).isEqualTo(401);
    }

    @Test
    void missingBatchManifestAndOlderGenerationNeverInferAbsenceOrOverwriteNewerState() {
        TenantContext tenant = tenant("generation-fence");
        UUID binding = connector(tenant, "runtime.test", "1.0", true);
        WorkerExternalSubject subject = new WorkerExternalSubject("https://issuer.example", "generation-worker");
        worker(tenant, subject, binding, WorkerCapability.RECONCILE);
        var service = service(revisionUnavailable());
        var registration = repository.findEnabledByExternalSubject(subject).orElseThrow();
        var session = service.establishSession(registration, "generation-pod", List.of(1),
                List.of(runtimeAdvertisement("runtime.test", "1.0", WorkerCapability.RECONCILE)));
        UUID older = createReconciliation(tenant, binding, "runtime.test", "1.0", 1);
        var oldLease = service.claim(registration, session.id(), 1, 0).getFirst();
        append(service, registration, session.id(), older, oldLease.lease().leaseId(), oldLease.lease().leaseEpoch(),
                List.of(observation("old-only", "Old")));
        UUID newer = createReconciliation(tenant, binding, "runtime.test", "1.0", 1);
        var newLease = service.claim(registration, session.id(), 1, 0).getFirst();
        append(service, registration, session.id(), newer, newLease.lease().leaseId(), newLease.lease().leaseEpoch(),
                List.of(observation("new-only", "New")));
        service.complete(registration, session.id(), newer, newLease.lease().leaseId(), newLease.lease().leaseEpoch(),
                success(ReconciliationCompleteness.COMPLETE));
        service.complete(registration, session.id(), older, oldLease.lease().leaseId(), oldLease.lease().leaseEpoch(),
                success(ReconciliationCompleteness.COMPLETE));
        assertThat(effectiveCompleteness(older)).isEqualTo("UNKNOWN");
        assertThat(present(tenant, binding, "new-only")).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM integration.observed_principal WHERE tenant_id=? AND provider_stable_id='old-only'",
                Integer.class, tenant.tenantId())).isZero();
        UUID incomplete = createReconciliation(tenant, binding, "runtime.test", "1.0", 1);
        var lease = service.claim(registration, session.id(), 1, 0).getFirst();
        append(service, registration, session.id(), incomplete, lease.lease().leaseId(), lease.lease().leaseEpoch(),
                List.of(observation("partial-only", "Partial")));
        var missing = new ConnectorWorkRepository.WorkCompletion("SUCCEEDED", null, null, null, null, null, null,
                Map.of("observationBatchCount", 2), ReconciliationCompleteness.COMPLETE, null);
        service.complete(registration, session.id(), incomplete, lease.lease().leaseId(), lease.lease().leaseEpoch(), missing);
        assertThat(effectiveCompleteness(incomplete)).isEqualTo("UNKNOWN");
        assertThat(present(tenant, binding, "new-only")).isTrue();
        assertThat(present(tenant, binding, "partial-only")).isTrue();
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
    void concurrentFirstAccountAdmissionsSerializeAcrossRevisions() throws Exception {
        TenantContext tenant=tenant("create-concurrency");
        UUID binding=connector(tenant,"runtime.test","1.0",true), cause=ids.nextId();
        UUID first=createProvisioning(tenant,binding,cause,1), second=createProvisioning(tenant,binding,cause,2);
        var a=new ConnectorWorkRepository.ProvisioningCandidate(first,ids.nextId(),tenant.tenantId(),binding,
                "UPSERT_PRINCIPAL","DESIRED_PRINCIPAL",cause,1,"test.principal",1,"first",ids.nextId(),null,Map.of());
        var b=new ConnectorWorkRepository.ProvisioningCandidate(second,ids.nextId(),tenant.tenantId(),binding,
                "UPSERT_PRINCIPAL","DESIRED_PRINCIPAL",cause,2,"test.principal",1,"second",ids.nextId(),null,Map.of());
        var acquired=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var firstClaim=executor.submit(()->transactions.required(()->{
                var decision=repository.principalCreationAdmission(a);
                acquired.countDown();
                try{if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("test timeout");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                jdbc.update("UPDATE integration.provisioning_task SET state='RUNNING' WHERE id=?",first);
                return decision;
            }));
            try {
                assertThat(acquired.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(transactions.required(()->repository.principalCreationAdmission(b)))
                        .isEqualTo(io.wyrmgate.iam.integration.application.ProvisioningAdmission.Decision.RETRY);
            } finally {release.countDown();}
            assertThat(firstClaim.get(10,java.util.concurrent.TimeUnit.SECONDS))
                    .isEqualTo(io.wyrmgate.iam.integration.application.ProvisioningAdmission.Decision.ADMIT);
        }
        assertThat(transactions.required(()->repository.principalCreationAdmission(b)))
                .isEqualTo(io.wyrmgate.iam.integration.application.ProvisioningAdmission.Decision.RETRY);
    }

    @Test
    void scheduledReconciliationPinsConfigurationAndFailsStaleWorkWithoutAbsence() {
        TenantContext tenant=tenant("scheduled-pins");
        UUID binding=connector(tenant,"runtime.test","1.0",true);
        var commands=new IntegrationReconciliationCommands(jdbc,ids,null,
                (t,target)->io.wyrmgate.iam.catalog.application.CatalogTargetReferenceQuery.Validation.VALID);
        var run=transactions.required(()->commands.schedule(tenant,binding,1,"PRINCIPAL","FULL",null,NOW,ids.nextId()));
        assertThat(jdbc.queryForObject("SELECT binding_revision FROM integration.reconciliation_run WHERE id=?",Long.class,run.id())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT scan_generation FROM integration.reconciliation_run WHERE id=?",Long.class,run.id())).isPositive();
        jdbc.update("UPDATE integration.connector_binding SET revision=2 WHERE id=?",binding);
        assertThat(transactions.required(()->repository.failStaleReconciliation(100,NOW.plusSeconds(1)))).isEqualTo(1);
        assertThat(commands.read(tenant,run.id()).state()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT effective_completeness FROM integration.reconciliation_run WHERE id=?",String.class,run.id())).isEqualTo("UNKNOWN");
    }

    @Test
    void uncertainCreateBlocksLaterDesiredRevisionAndPreservesAttemptEvidence() {
        TenantContext tenant=tenant("uncertain-create-fence");
        UUID binding=connector(tenant,"runtime.test","1.0",true);
        UUID cause=ids.nextId();
        UUID task=createProvisioning(tenant,binding,cause,1);
        WorkerExternalSubject workerSubject=new WorkerExternalSubject("https://issuer.example","create-fence-worker");
        worker(tenant,workerSubject,binding,WorkerCapability.PROVISION);
        var registration=repository.findEnabledByExternalSubject(workerSubject).orElseThrow();
        var service=service((t,kind,id)->Freshness.current(1));
        var session=service.establishSession(registration,"pod-create-fence",List.of(1),
                List.of(runtimeAdvertisement("runtime.test","1.0",WorkerCapability.PROVISION)));
        var lease=service.claim(registration,session.id(),1,0).getFirst();
        service.complete(registration,session.id(),task,lease.lease().leaseId(),lease.lease().leaseEpoch(),
                new ConnectorWorkRepository.WorkCompletion("FAILED_RETRYABLE","TRANSIENT","transport_failure",
                        null,null,null,null,Map.of(),null,null));
        assertThat(taskState(task)).isEqualTo("BLOCKED");
        assertThat(jdbc.queryForObject("SELECT failure_code FROM integration.provisioning_task WHERE id=?",String.class,task))
                .isEqualTo("provider_create_effect_unknown");
        assertThat(jdbc.queryForObject("SELECT outcome FROM integration.provisioning_attempt WHERE task_id=?",String.class,task))
                .isEqualTo("FAILED_RETRYABLE");
        UUID next=createProvisioning(tenant,binding,cause,2);
        var candidate=new ConnectorWorkRepository.ProvisioningCandidate(next,ids.nextId(),tenant.tenantId(),binding,
                "UPSERT_PRINCIPAL","DESIRED_PRINCIPAL",cause,2,"test.principal",1,"new-key",ids.nextId(),null,Map.of());
        assertThat(transactions.required(()->repository.principalCreationAdmission(candidate)))
                .isEqualTo(io.wyrmgate.iam.integration.application.ProvisioningAdmission.Decision.RETRY);
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
    void expiredMutationIsBlockedAsUncertainAndNeverBlindlyReplayed() {
        TenantContext tenant = tenant("expired-mutation");
        UUID binding = connector(tenant, "runtime.test", "1.0", true);
        WorkerExternalSubject subject = new WorkerExternalSubject("https://issuer.example", "expired-worker");
        worker(tenant, subject, binding, WorkerCapability.PROVISION);
        var registration = repository.findEnabledByExternalSubject(subject).orElseThrow();
        UUID taskId = createProvisioning(tenant, binding, "DESIRED_GRANT", ids.nextId(), 6);
        var service = service((t, kind, id) -> Freshness.current(6));
        var session = service.establishSession(registration, "pod-expired", List.of(1),
                List.of(runtimeAdvertisement("runtime.test", "1.0", WorkerCapability.PROVISION)));
        var lease = service.claim(registration, session.id(), 1, 0).getFirst();
        assertThat(transactions.required(() -> repository.blockExpiredProvisioning(100, Instant.now()))).isZero();
        jdbc.update("UPDATE platform.connector_work_lease SET claimed_at=?, lease_expires_at=?, updated_at=? WHERE tenant_id=? AND work_id=?",
                Timestamp.from(NOW), Timestamp.from(NOW.plusSeconds(1)), Timestamp.from(NOW.plusSeconds(1)), tenant.tenantId(), taskId);
        // Preserve a valid lease timestamp constraint for the simulated worker loss.
        assertThat(transactions.required(() -> repository.blockExpiredProvisioning(100, Instant.now()))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM integration.provisioning_task WHERE tenant_id=? AND id=?",
                String.class, tenant.tenantId(), taskId)).isEqualTo("BLOCKED");
        assertThat(jdbc.queryForObject("SELECT failure_code FROM integration.provisioning_task WHERE tenant_id=? AND id=?",
                String.class, tenant.tenantId(), taskId)).isEqualTo("provider_effect_unknown_after_lease_expiry");
        assertThat(service.claim(registration, session.id(), 1, 0)).isEmpty();
        assertThat(transactions.required(() -> repository.blockExpiredProvisioning(100, Instant.now()))).isZero();
        assertThatThrownBy(() -> service.complete(registration, session.id(), taskId, lease.lease().leaseId(),
                lease.lease().leaseEpoch(), success(ReconciliationCompleteness.UNKNOWN)))
                .isInstanceOf(WorkerProtocolException.class);
        var late=new ConnectorWorkRepository.WorkCompletion("SUCCEEDED",null,null,"provider-request",null,
                "provider-object",null,Map.of(),null,null);
        assertThat(service.complete(registration,session.id(),taskId,lease.lease().leaseId(),lease.lease().leaseEpoch(),late))
                .isEqualTo(ConnectorWorkRepository.CompletionResult.ACCEPTED);
        assertThat(service.complete(registration,session.id(),taskId,lease.lease().leaseId(),lease.lease().leaseEpoch(),late))
                .isEqualTo(ConnectorWorkRepository.CompletionResult.REPLAY);
        assertThat(taskState(taskId)).isEqualTo("BLOCKED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM integration.provisioning_effect_recovery WHERE tenant_id=?",
                Integer.class,tenant.tenantId())).isEqualTo(1);
        assertThat(service.claim(registration,session.id(),1,0)).isEmpty();
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

    @Test
    void verifiedRecoveryRequiresCurrentFullEvidenceAndCreatesOnlyOneCorrectivePlan() {
        TenantContext tenant=tenant("verified-recovery");
        TenantContext other=tenant("foreign-recovery");
        UUID binding=groupConnector(tenant,true,true);
        UUID target=jdbc.queryForObject("SELECT target_id FROM integration.connector_binding WHERE id=?",UUID.class,binding);
        UUID cause=ids.nextId(), principal=ids.nextId(), entitlement=ids.nextId(), identity=ids.nextId();
        UUID task=createProvisioning(tenant,binding,"DESIRED_GRANT",cause,1);
        Instant blocked=NOW.minusSeconds(20);
        jdbc.update("""
                UPDATE integration.provisioning_job SET planned_binding_revision=1,planned_connector_revision=1,
                    planned_configuration_version=1 WHERE id=(SELECT provisioning_job_id FROM integration.provisioning_task WHERE id=?)
                """,task);
        jdbc.update("""
                UPDATE integration.provisioning_task SET contract_id='test.group',operation_type='ADD_GRANT',
                    state='BLOCKED',failure_code='provider_effect_unknown_after_lease_expiry',revision=2,
                    attempt_count=1,created_at=?,updated_at=?,payload=?::jsonb WHERE id=?
                """,Timestamp.from(blocked),Timestamp.from(blocked),
                "{\"principalId\":\""+principal+"\",\"entitlementId\":\""+entitlement+"\",\"providerPrincipalId\":\"u-a\",\"providerEntitlementId\":\"g-a\"}",task);
        var desired=new DesiredProvisioningStateQuery(){
            public Result desiredGrant(TenantContext t,UUID id){return Result.current(new DesiredGrantState(cause,identity,target,entitlement,"ANY",principal,DesiredPresence.ABSENT,2,1,NOW));}
            public PrincipalResult desiredPrincipal(TenantContext t,UUID id){return PrincipalResult.unavailable();}
            public TechnicalGrantDemand technicalGrantDemand(TenantContext t,UUID a,UUID e,UUID p){return TechnicalGrantDemand.NOT_REQUIRED;}
        };
        var recovery=new VerifiedProvisioningRecovery(jdbc,json,ids,desired,new JdbcGrantProvisioningRepository(jdbc,json,ids),
                new JdbcPrincipalProvisioningRepository(jdbc,json,ids),new PrincipalTechnicalReferenceQuery(){
                    public Result resolve(TenantContext t,UUID id){return Result.unavailable();}
                    public List<Result> activeForIdentityTarget(TenantContext t,UUID i,UUID a){return List.of();}
                    public List<Result> forIdentityTarget(TenantContext t,UUID i,UUID a){return List.of();}
                },repository);
        WorkerExternalSubject workerSubject=new WorkerExternalSubject("https://issuer.example","verified-recovery-worker");
        groupWorker(tenant,workerSubject,binding,WorkerCapability.RECONCILE);
        var service=service(revisionUnavailable());
        var registration=repository.findEnabledByExternalSubject(workerSubject).orElseThrow();
        var session=service.establishSession(registration,"recovery",List.of(1),
                List.of(groupRuntimeAdvertisement(WorkerCapability.RECONCILE)));
        UUID partial=createGroupReconciliation(tenant,binding,"GRANT");
        var lease=service.claim(registration,session.id(),1,0).getFirst();
        append(service,registration,session.id(),partial,lease.lease().leaseId(),lease.lease().leaseEpoch(),
                List.of(new ConnectorWorkRepository.ProviderObservation("GRANT","grant-a","v1",
                        Map.of("principalProviderId","u-a","entitlementProviderId","g-a"))));
        service.complete(registration,session.id(),partial,lease.lease().leaseId(),lease.lease().leaseEpoch(),success(ReconciliationCompleteness.PARTIAL));
        UUID operator=ids.nextId(), correlation=ids.nextId();
        Instant quiesced=NOW.minusSeconds(10);
        assertThatThrownBy(()->transactions.required(()->recovery.recover(tenant,task,2,partial,null,quiesced,operator,Instant.now(),correlation)))
                .isInstanceOf(IntegrationAdministrationException.class).hasMessageContaining("complete full scan");

        UUID full=createGroupReconciliation(tenant,binding,"GRANT");
        var next=service.claim(registration,session.id(),1,0).getFirst();
        append(service,registration,session.id(),full,next.lease().leaseId(),next.lease().leaseEpoch(),
                List.of(new ConnectorWorkRepository.ProviderObservation("GRANT","grant-a","v2",
                        Map.of("principalProviderId","u-a","entitlementProviderId","g-a"))));
        service.complete(registration,session.id(),full,next.lease().leaseId(),next.lease().leaseEpoch(),success(ReconciliationCompleteness.COMPLETE));
        assertThat(effectiveCompleteness(full)).isEqualTo("COMPLETE");
        assertThatThrownBy(()->transactions.required(()->recovery.recover(other,task,2,full,null,quiesced,operator,Instant.now(),correlation)))
                .isInstanceOf(IntegrationAdministrationException.class).hasMessageContaining("not found");
        assertThatThrownBy(()->transactions.required(()->recovery.recover(tenant,task,1,full,null,quiesced,operator,Instant.now(),correlation)))
                .isInstanceOf(IntegrationAdministrationException.class).hasMessageContaining("revision");
        assertThatThrownBy(()->transactions.required(()->recovery.recover(tenant,task,2,full,null,NOW.plusSeconds(1),operator,Instant.now(),correlation)))
                .isInstanceOf(IntegrationAdministrationException.class).hasMessageContaining("quiesced route");
        var decision=transactions.required(()->recovery.recover(tenant,task,2,full,null,quiesced,operator,Instant.now(),correlation));
        assertThat(decision.disposition()).isEqualTo("CORRECTIVE_PLAN_CREATED");
        assertThat(decision.observation()).isEqualTo("PRESENT");
        assertThat(decision.desiredRevision()).isEqualTo(2);
        assertThat(taskState(task)).isEqualTo("BLOCKED");
        assertThat(attemptCount(task)).isZero();
        assertThat(jdbc.queryForObject("SELECT operation_type FROM integration.provisioning_task WHERE provisioning_job_id=?",
                String.class,decision.correctiveJobId())).isEqualTo("REMOVE_GRANT");
        assertThat(recovery.read(tenant,decision.id()).id()).isEqualTo(decision.id());
        assertThatThrownBy(()->transactions.required(()->recovery.recover(tenant,task,2,full,null,quiesced,operator,Instant.now(),correlation)))
                .isInstanceOf(IntegrationAdministrationException.class).hasMessageContaining("already has a recovery decision");
        assertThatThrownBy(()->recovery.read(other,decision.id())).isInstanceOf(IntegrationAdministrationException.class);
        var projected=new JdbcIntegrationOperationalStatusRepository(jdbc).findProvisioningTask(tenant,task).orElseThrow();
        assertThat(projected.recoveryDecisionId()).isEqualTo(decision.id());
        assertThat(projected.recoveryCorrectiveJobId()).isEqualTo(decision.correctiveJobId());
        assertThat(projected.state()).isEqualTo("BLOCKED");
        assertThatThrownBy(()->jdbc.update("UPDATE integration.provisioning_recovery_decision SET disposition='MATCHES_DESIRED' WHERE id=?",decision.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void uncertainCreateRequiresPositiveExactMappedPrincipalAndNeverCreatesAnotherAccount() {
        TenantContext tenant=tenant("uncertain-create-recovery");
        UUID binding=connector(tenant,"runtime.test","1.0",true);
        UUID target=jdbc.queryForObject("SELECT target_id FROM integration.connector_binding WHERE id=?",UUID.class,binding);
        UUID cause=ids.nextId(), principal=ids.nextId(), identity=ids.nextId(), operator=ids.nextId(), correlation=ids.nextId();
        UUID task=createProvisioning(tenant,binding,cause,1);
        Instant blocked=NOW.minusSeconds(20), quiesced=NOW.minusSeconds(10);
        jdbc.update("UPDATE integration.provisioning_job SET planned_binding_revision=1,planned_connector_revision=1,planned_configuration_version=1 WHERE id=(SELECT provisioning_job_id FROM integration.provisioning_task WHERE id=?)",task);
        jdbc.update("""
                UPDATE integration.provisioning_task SET state='BLOCKED',failure_code='provider_effect_unknown_after_lease_expiry',
                    revision=2,attempt_count=1,created_at=?,updated_at=?,payload=?::jsonb WHERE id=?
                """,Timestamp.from(blocked),Timestamp.from(blocked),
                "{\"identityId\":\""+identity+"\",\"applicationTargetId\":\""+target+"\",\"userName\":\"never-a-correlation-key\"}",task);
        var desired=new DesiredProvisioningStateQuery(){
            public Result desiredGrant(TenantContext t,UUID id){return Result.unavailable();}
            public PrincipalResult desiredPrincipal(TenantContext t,UUID id){return PrincipalResult.current(new DesiredPrincipalState(cause,identity,target,DesiredPresence.PRESENT,2,1,NOW));}
            public TechnicalGrantDemand technicalGrantDemand(TenantContext t,UUID a,UUID e,UUID p){return TechnicalGrantDemand.UNAVAILABLE;}
        };
        var recovery=new VerifiedProvisioningRecovery(jdbc,json,ids,desired,new JdbcGrantProvisioningRepository(jdbc,json,ids),
                new JdbcPrincipalProvisioningRepository(jdbc,json,ids),new PrincipalTechnicalReferenceQuery(){
                    public Result resolve(TenantContext t,UUID id){return id.equals(principal)?Result.active(principal,identity,target,"not-provider-correlation"):Result.unavailable();}
                    public List<Result> activeForIdentityTarget(TenantContext t,UUID i,UUID a){return List.of();}
                    public List<Result> forIdentityTarget(TenantContext t,UUID i,UUID a){return List.of();}
                },repository);
        WorkerExternalSubject workerSubject=new WorkerExternalSubject("https://issuer.example","uncertain-create-worker");
        worker(tenant,workerSubject,binding,WorkerCapability.RECONCILE);
        var service=service(revisionUnavailable());
        var registration=repository.findEnabledByExternalSubject(workerSubject).orElseThrow();
        var session=service.establishSession(registration,"recover-create",List.of(1),
                List.of(runtimeAdvertisement("runtime.test","1.0",WorkerCapability.RECONCILE)));
        UUID full=createReconciliation(tenant,binding,"runtime.test","1.0",1);
        var lease=service.claim(registration,session.id(),1,0).getFirst();
        append(service,registration,session.id(),full,lease.lease().leaseId(),lease.lease().leaseEpoch(),
                List.of(new ConnectorWorkRepository.ProviderObservation("PRINCIPAL","provider-account","v1",
                        Map.of("active",true,"userName","never-a-correlation-key"))));
        service.complete(registration,session.id(),full,lease.lease().leaseId(),lease.lease().leaseEpoch(),success(ReconciliationCompleteness.COMPLETE));
        assertThatThrownBy(()->transactions.required(()->recovery.recover(tenant,task,2,full,null,quiesced,operator,Instant.now(),correlation)))
                .isInstanceOf(IntegrationAdministrationException.class).hasMessageContaining("explicitly mapped");
        assertThatThrownBy(()->transactions.required(()->recovery.recover(tenant,task,2,full,principal,quiesced,operator,Instant.now(),correlation)))
                .isInstanceOf(IntegrationAdministrationException.class).hasMessageContaining("active principal mapping");
        jdbc.update("""
                INSERT INTO integration.principal_observation_mapping
                    (id,tenant_id,connector_binding_id,provider_stable_id,principal_id,lifecycle_state,revision,created_at,updated_at)
                VALUES (?,?,?,'provider-account',?,'ACTIVE',1,?,?)
                """,ids.nextId(),tenant.tenantId(),binding,principal,Timestamp.from(NOW),Timestamp.from(NOW));
        var decision=transactions.required(()->recovery.recover(tenant,task,2,full,principal,quiesced,operator,Instant.now(),correlation));
        assertThat(decision.disposition()).isEqualTo("MATCHES_DESIRED");
        assertThat(decision.observation()).isEqualTo("ACTIVE");
        assertThat(decision.correctiveJobId()).isNull();
        assertThat(taskState(task)).isEqualTo("BLOCKED");
        assertThat(attemptCount(task)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM integration.provisioning_task WHERE tenant_id=?",Integer.class,tenant.tenantId())).isEqualTo(1);
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
                json,c->io.wyrmgate.iam.integration.application.ProvisioningAdmission.Decision.ADMIT);
    }

    private static DesiredAccessStateQuery revisionUnavailable() {
        return (tenant, subjectKind, subjectId) -> Freshness.unavailable();
    }

    private TenantContext tenant(String name) {
        return new TenantContext(tenants.createActive(name, NOW).id());
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
                completeness == ReconciliationCompleteness.COMPLETE ? Map.of("observationBatchCount", 1) : Map.of(),
                completeness, completeness == ReconciliationCompleteness.COMPLETE ? null : "checkpoint-next");
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
