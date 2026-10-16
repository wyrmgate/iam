package io.wyrmgate.iam.administration.persistence;

import io.wyrmgate.iam.administration.application.PlatformOwnerAdoptionFactSink;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.OutboxEvent;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Data-minimized transactional outbox adapter for explicit legacy ownership adoption. */
public final class JdbcPlatformOwnerAdoptionFactSink implements PlatformOwnerAdoptionFactSink {

    private final JdbcOutboxRepository outbox;
    private final IdGenerator ids;

    public JdbcPlatformOwnerAdoptionFactSink(JdbcOutboxRepository outbox, IdGenerator ids) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    public void adopted(
            TenantContext tenant,
            UUID identityId,
            UUID principalId,
            UUID tenantOwnerAuthorityId,
            UUID platformOwnerAuthorityId,
            Instant adoptedAt,
            UUID correlationId) {
        String payload = "{\"actorIdentityId\":\"" + identityId
                + "\",\"actorPrincipalId\":\"" + principalId
                + "\",\"tenantOwnerAuthorityId\":\"" + tenantOwnerAuthorityId
                + "\",\"platformOwnerAuthorityId\":\"" + platformOwnerAuthorityId + "\"}";
        outbox.append(
                tenant,
                new OutboxEvent(
                        ids.nextId(),
                        "administration.platform-owner-adopted",
                        1,
                        "platform-owner-authority",
                        platformOwnerAuthorityId,
                        1L,
                        adoptedAt,
                        correlationId,
                        null,
                        payload),
                adoptedAt);
    }
}
