package io.wyrmgate.iam.identity.persistence;

import io.wyrmgate.iam.identity.application.SourceCorrelationFactSink;
import io.wyrmgate.iam.identity.domain.IdentityLink;
import io.wyrmgate.iam.identity.domain.SourceImportRun;
import io.wyrmgate.iam.identity.domain.SourceRecord;
import io.wyrmgate.iam.identity.domain.SourceSystem;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.OutboxEvent;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;

/** Internal Identity source/correlation facts; these are not automatically public integration events. */
public final class JdbcSourceCorrelationFactSink implements SourceCorrelationFactSink {

    private final JdbcOutboxRepository outboxRepository;
    private final IdGenerator idGenerator;

    public JdbcSourceCorrelationFactSink(JdbcOutboxRepository outboxRepository, IdGenerator idGenerator) {
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    }

    @Override
    public void sourceSystemCreated(
            TenantContext tenant,
            SourceSystem sourceSystem,
            UUID correlationId,
            UUID causationId) {
        outboxRepository.append(
                tenant,
                new OutboxEvent(
                        idGenerator.nextId(),
                        "identity.source-system-created",
                        1,
                        "source-system",
                        sourceSystem.id(),
                        sourceSystem.revision(),
                        sourceSystem.updatedAt(),
                        correlationId,
                        causationId,
                        "{\"created\":true}"),
                sourceSystem.updatedAt());
    }

    @Override
    public void sourceRecordObserved(
            TenantContext tenant,
            SourceRecord sourceRecord,
            UUID correlationId,
            UUID causationId) {
        outboxRepository.append(
                tenant,
                new OutboxEvent(
                        idGenerator.nextId(),
                        "identity.source-record-observed",
                        1,
                        null,
                        null,
                        null,
                        sourceRecord.lastObservedAt(),
                        correlationId,
                        causationId,
                        "{\"sourceRecordId\":\"" + sourceRecord.id() + "\"}"),
                sourceRecord.lastObservedAt());
    }

    @Override
    public void importCompleted(
            TenantContext tenant,
            SourceImportRun run,
            UUID correlationId,
            UUID causationId) {
        outboxRepository.append(
                tenant,
                new OutboxEvent(
                        idGenerator.nextId(),
                        "identity.source-import-completed",
                        1,
                        null,
                        null,
                        null,
                        run.completedAt(),
                        correlationId,
                        causationId,
                        "{\"runId\":\"" + run.id() + "\",\"completeness\":\""
                                + run.completeness().name() + "\"}"),
                run.completedAt());
    }

    @Override
    public void identityLinkAccepted(TenantContext tenant, IdentityLink link) {
        outboxRepository.append(
                tenant,
                new OutboxEvent(
                        idGenerator.nextId(),
                        "identity.identity-link-accepted",
                        1,
                        null,
                        null,
                        null,
                        link.linkedAt(),
                        link.correlationId(),
                        link.causationId(),
                        "{\"identityLinkId\":\"" + link.id() + "\",\"sourceRecordId\":\""
                                + link.sourceRecordId() + "\",\"identityId\":\"" + link.identityId() + "\"}"),
                link.linkedAt());
    }
}
