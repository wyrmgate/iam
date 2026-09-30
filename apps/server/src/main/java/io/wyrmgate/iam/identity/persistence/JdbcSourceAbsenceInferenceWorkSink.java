package io.wyrmgate.iam.identity.persistence;

import io.wyrmgate.iam.identity.application.SourceAbsenceInferenceWorkSink;
import io.wyrmgate.iam.identity.domain.SourceAbsenceInference;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.OutboxEvent;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;

public final class JdbcSourceAbsenceInferenceWorkSink implements SourceAbsenceInferenceWorkSink {

    private final JdbcOutboxRepository outbox;
    private final IdGenerator ids;

    public JdbcSourceAbsenceInferenceWorkSink(JdbcOutboxRepository outbox, IdGenerator ids) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    public void inferenceRequested(
            TenantContext tenant,
            SourceAbsenceInference inference,
            UUID correlationId,
            UUID causationId) {
        UUID eventId = ids.nextId();
        outbox.append(
                tenant,
                new OutboxEvent(
                        eventId,
                        INFERENCE_REQUESTED,
                        1,
                        "source-absence-inference",
                        inference.id(),
                        inference.revision(),
                        inference.updatedAt(),
                        correlationId == null ? eventId : correlationId,
                        causationId,
                        "{}"),
                inference.updatedAt());
    }
}
