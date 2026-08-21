package io.wyrmgate.iam.identity.persistence;

import io.wyrmgate.iam.identity.application.CanonicalAttributeFactSink;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeOverride;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeState;
import io.wyrmgate.iam.identity.domain.CanonicalSchemaVersion;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.OutboxEvent;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;

/** Data-minimized mapping of canonical attribute semantic facts to the transactional outbox. */
public final class JdbcCanonicalAttributeFactSink implements CanonicalAttributeFactSink {

    private final JdbcOutboxRepository outbox;
    private final IdGenerator ids;

    public JdbcCanonicalAttributeFactSink(JdbcOutboxRepository outbox, IdGenerator ids) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    public void schemaActivated(
            TenantContext tenant, CanonicalSchemaVersion schema, UUID correlationId, UUID causationId) {
        outbox.append(tenant, new OutboxEvent(
                ids.nextId(), "identity.canonical-schema-activated", 1,
                "canonical-schema", schema.id(), schema.versionNumber(), schema.activatedAt(),
                correlationId, causationId,
                "{\"schemaVersionNumber\":" + schema.versionNumber() + "}"), schema.activatedAt());
    }

    @Override
    public void stateChanged(
            TenantContext tenant, CanonicalAttributeState state, String canonicalKey,
            UUID correlationId, UUID causationId) {
        outbox.append(tenant, new OutboxEvent(
                ids.nextId(), "identity.canonical-attribute-state-changed", 1,
                "canonical-attribute-state", state.id(), state.valueRevision(), state.updatedAt(),
                correlationId, causationId,
                "{\"canonicalKey\":\"" + escape(canonicalKey) + "\",\"resolutionStatus\":\""
                        + state.resolutionStatus().name() + "\"}"), state.updatedAt());
    }

    @Override
    public void overrideApplied(TenantContext tenant, CanonicalAttributeOverride override, String canonicalKey) {
        outbox.append(tenant, new OutboxEvent(
                ids.nextId(), "identity.canonical-attribute-override-applied", 1,
                "canonical-attribute-override", override.id(), override.revision(), override.createdAt(),
                override.correlationId(), override.causationId(),
                "{\"canonicalKey\":\"" + escape(canonicalKey) + "\"}"), override.createdAt());
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
