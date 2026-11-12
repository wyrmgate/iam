package io.wyrmgate.iam.catalog.application;

import io.wyrmgate.iam.catalog.domain.CatalogAvailabilityCauseKind;
import io.wyrmgate.iam.catalog.domain.CatalogAvailabilityPropagation;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Starts/reuses one Catalog-owned durable propagation for a committed retirement cause. */
public final class CatalogAvailabilityPropagationStarter {

    private final CatalogAvailabilityPropagationRepository propagations;
    private final CatalogAvailabilityPropagationWorkSink work;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;

    public CatalogAvailabilityPropagationStarter(
            CatalogAvailabilityPropagationRepository propagations,
            CatalogAvailabilityPropagationWorkSink work,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this.propagations = Objects.requireNonNull(propagations, "propagations");
        this.work = Objects.requireNonNull(work, "work");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public CatalogAvailabilityPropagation start(
            TenantContext tenant,
            CatalogAvailabilityCauseKind causeKind,
            UUID causeId,
            long causeRevision,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(causeKind, "causeKind");
        Objects.requireNonNull(causeId, "causeId");
        Objects.requireNonNull(now, "now");
        if (causeRevision < 1) {
            throw new IllegalArgumentException("causeRevision must be positive");
        }

        CatalogAvailabilityPropagation candidate =
                new CatalogAvailabilityPropagation(
                        ids.nextId(),
                        causeKind,
                        causeId,
                        causeRevision,
                        initialPhase(causeKind),
                        null,
                        null,
                        0,
                        1,
                        CatalogAvailabilityPropagation.State.RUNNING,
                        now,
                        now,
                        null);

        return transactions.required(() -> {
            CatalogAvailabilityPropagation current =
                    propagations.startIfAbsent(tenant, candidate);
            if (current.id().equals(candidate.id())) {
                work.requested(
                        tenant,
                        current,
                        correlationId,
                        causationId);
            }
            return current;
        });
    }

    private static CatalogAvailabilityPropagation.Phase initialPhase(
            CatalogAvailabilityCauseKind causeKind) {
        return switch (causeKind) {
            case APPLICATION, APPLICATION_TARGET, ENTITLEMENT ->
                    CatalogAvailabilityPropagation.Phase.ENTITLEMENTS;
            case ROLE -> CatalogAvailabilityPropagation.Phase.ROLES;
        };
    }
}
