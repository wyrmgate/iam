package io.wyrmgate.iam.identity.persistence;

import io.wyrmgate.iam.identity.application.PrincipalFactSink;
import io.wyrmgate.iam.identity.domain.Principal;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.OutboxEvent;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;

public final class JdbcPrincipalFactSink implements PrincipalFactSink {

    private final JdbcOutboxRepository outbox;
    private final IdGenerator ids;

    public JdbcPrincipalFactSink(
            JdbcOutboxRepository outbox, IdGenerator ids) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    public void principalCorrelated(
            TenantContext tenant,
            Principal principal,
            UUID correlationId,
            UUID causationId) {
        append(
                tenant,
                PRINCIPAL_CORRELATED,
                principal,
                correlationId,
                causationId,
                null);
        append(
                tenant,
                ACCESS_PROJECTION_INPUT_CHANGED,
                principal,
                correlationId,
                causationId,
                null);
    }

    @Override
    public void principalAccessProjectionChanged(
            TenantContext tenant,
            Principal principal,
            UUID correlationId,
            UUID causationId) {
        append(
                tenant,
                ACCESS_PROJECTION_INPUT_CHANGED,
                principal,
                correlationId,
                causationId,
                null);
    }

    @Override
    public void principalReassigned(
            TenantContext tenant,
            Principal principal,
            UUID previousIdentityId,
            UUID correlationId,
            UUID causationId) {
        append(
                tenant,
                PRINCIPAL_CORRELATED,
                principal,
                correlationId,
                causationId,
                previousIdentityId);
        append(
                tenant,
                ACCESS_PROJECTION_INPUT_CHANGED,
                principal,
                correlationId,
                causationId,
                previousIdentityId);
    }

    private void append(
            TenantContext tenant,
            String eventType,
            Principal principal,
            UUID correlationId,
            UUID causationId,
            UUID previousIdentityId) {
        String payload = "{\"applicationTargetId\":\""
                + principal.applicationTargetId()
                + "\""
                + (previousIdentityId == null
                        ? ""
                        : ",\"previousIdentityId\":\"" + previousIdentityId + "\"")
                + "}";
        outbox.append(
                tenant,
                new OutboxEvent(
                        ids.nextId(),
                        eventType,
                        1,
                        "principal",
                        principal.id(),
                        principal.revision(),
                        principal.updatedAt(),
                        correlationId,
                        causationId,
                        payload),
                principal.updatedAt());
    }
}
