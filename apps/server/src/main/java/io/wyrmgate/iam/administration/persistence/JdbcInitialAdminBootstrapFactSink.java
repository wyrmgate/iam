package io.wyrmgate.iam.administration.persistence;

import io.wyrmgate.iam.administration.application.InitialAdminBootstrapFactSink;
import io.wyrmgate.iam.administration.domain.InitialAdminBootstrap;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.OutboxEvent;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;

/** Data-minimized transactional outbox adapter for initial-administrator bootstrap facts. */
public final class JdbcInitialAdminBootstrapFactSink implements InitialAdminBootstrapFactSink {

    private final JdbcOutboxRepository outboxRepository;
    private final IdGenerator idGenerator;

    public JdbcInitialAdminBootstrapFactSink(JdbcOutboxRepository outboxRepository, IdGenerator idGenerator) {
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    }

    @Override
    public void bootstrapped(TenantContext tenant, InitialAdminBootstrap bootstrap) {
        String payload = "{\"actorIdentityId\":\"" + bootstrap.actorIdentityId()
                + "\",\"actorBindingId\":\"" + bootstrap.actorBindingId()
                + "\",\"administrativeRoleId\":\"" + bootstrap.administrativeRoleId()
                + "\",\"administrativeGrantId\":\"" + bootstrap.administrativeGrantId() + "\"}";
        outboxRepository.append(
                tenant,
                new OutboxEvent(
                        idGenerator.nextId(),
                        "administration.initial-admin-bootstrapped",
                        1,
                        "initial-admin-bootstrap",
                        bootstrap.id(),
                        1L,
                        bootstrap.completedAt(),
                        bootstrap.correlationId(),
                        null,
                        payload),
                bootstrap.completedAt());
    }
}
