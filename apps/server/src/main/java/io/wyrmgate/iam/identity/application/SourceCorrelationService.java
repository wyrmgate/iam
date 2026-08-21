package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.identity.domain.IdentityLink;
import io.wyrmgate.iam.identity.domain.SourceImportCompleteness;
import io.wyrmgate.iam.identity.domain.SourceImportRun;
import io.wyrmgate.iam.identity.domain.SourceImportRunState;
import io.wyrmgate.iam.identity.domain.SourceRecord;
import io.wyrmgate.iam.identity.domain.SourceSystem;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Identity-owned commands for source definition, positive import observation and explicit correlation. */
public final class SourceCorrelationService {

    private final SourceCorrelationRepository repository;
    private final IdentityRepository identityRepository;
    private final SourceCorrelationFactSink facts;
    private final IdGenerator idGenerator;
    private final TransactionExecutor transactions;

    public SourceCorrelationService(
            SourceCorrelationRepository repository,
            IdentityRepository identityRepository,
            SourceCorrelationFactSink facts,
            IdGenerator idGenerator,
            TransactionExecutor transactions) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.identityRepository = Objects.requireNonNull(identityRepository, "identityRepository");
        this.facts = Objects.requireNonNull(facts, "facts");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public SourceSystem createSourceSystem(
            TenantContext tenant,
            String code,
            String name,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(correlationId, "correlationId");
        SourceSystem sourceSystem = new SourceSystem(idGenerator.nextId(), code, name, 1, now, now);
        return transactions.required(() -> {
            repository.insertSourceSystem(tenant, sourceSystem);
            SourceSystem persisted = repository.findSourceSystem(tenant, sourceSystem.id())
                    .orElseThrow(() -> new IllegalStateException("source system could not be reloaded"));
            facts.sourceSystemCreated(tenant, persisted, correlationId, causationId);
            return persisted;
        });
    }

    public SourceImportRun startImport(
            TenantContext tenant,
            UUID sourceSystemId,
            Instant startedAt) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(sourceSystemId, "sourceSystemId");
        Objects.requireNonNull(startedAt, "startedAt");
        requireSourceSystem(tenant, sourceSystemId);
        SourceImportRun run = new SourceImportRun(
                idGenerator.nextId(),
                sourceSystemId,
                SourceImportRunState.RUNNING,
                SourceImportCompleteness.UNKNOWN,
                startedAt,
                null,
                null,
                null);
        return transactions.required(() -> {
            repository.insertImportRun(tenant, run);
            return repository.findImportRun(tenant, run.id())
                    .orElseThrow(() -> new IllegalStateException("source import run could not be reloaded"));
        });
    }

    public SourceRecord observe(
            TenantContext tenant,
            UUID importRunId,
            String nativeKey,
            String nativePayloadJson,
            Instant sourceUpdatedAt,
            Instant observedAt,
            UUID correlationId,
            UUID causationId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(importRunId, "importRunId");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(correlationId, "correlationId");
        SourceImportRun run = repository.findImportRun(tenant, importRunId)
                .orElseThrow(() -> new IllegalArgumentException("source import run does not exist"));
        if (run.state() != SourceImportRunState.RUNNING) {
            throw new IllegalStateException("source observations require a running import");
        }
        return transactions.required(() -> {
            SourceRecord record = repository.upsertPositiveObservation(
                    tenant,
                    run.sourceSystemId(),
                    run.id(),
                    nativeKey,
                    nativePayloadJson,
                    sourceUpdatedAt,
                    observedAt);
            facts.sourceRecordObserved(tenant, record, correlationId, causationId);
            return record;
        });
    }

    public SourceImportRun completeImport(
            TenantContext tenant,
            UUID runId,
            SourceImportCompleteness completeness,
            String checkpointToken,
            String partialReason,
            Instant completedAt,
            UUID correlationId,
            UUID causationId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(completeness, "completeness");
        Objects.requireNonNull(completedAt, "completedAt");
        Objects.requireNonNull(correlationId, "correlationId");
        if (completeness == SourceImportCompleteness.UNKNOWN) {
            throw new IllegalArgumentException("completed import completeness must be COMPLETE or PARTIAL");
        }
        return transactions.required(() -> {
            SourceImportRun run = repository.completeImportRun(
                    tenant, runId, completeness, checkpointToken, partialReason, completedAt);
            facts.importCompleted(tenant, run, correlationId, causationId);
            return run;
        });
    }

    public IdentityLink acceptCorrelation(
            TenantContext tenant,
            UUID sourceRecordId,
            UUID identityId,
            String correlationReason,
            Instant linkedAt,
            UUID correlationId,
            UUID causationId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(sourceRecordId, "sourceRecordId");
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(linkedAt, "linkedAt");
        Objects.requireNonNull(correlationId, "correlationId");
        if (repository.findSourceRecord(tenant, sourceRecordId).isEmpty()) {
            throw new IllegalArgumentException("source record does not exist");
        }
        if (identityRepository.findById(tenant, identityId).isEmpty()) {
            throw new IllegalArgumentException("identity does not exist");
        }
        return transactions.required(() -> {
            IdentityLink before = repository.findActiveAcceptedLink(tenant, sourceRecordId).orElse(null);
            IdentityLink accepted = repository.replaceAcceptedLink(
                    tenant,
                    sourceRecordId,
                    identityId,
                    correlationReason,
                    linkedAt,
                    correlationId,
                    causationId,
                    idGenerator.nextId());
            if (before == null || !before.id().equals(accepted.id())) {
                facts.identityLinkAccepted(tenant, accepted);
            }
            return accepted;
        });
    }

    private void requireSourceSystem(TenantContext tenant, UUID sourceSystemId) {
        if (repository.findSourceSystem(tenant, sourceSystemId).isEmpty()) {
            throw new IllegalArgumentException("source system does not exist");
        }
    }
}
