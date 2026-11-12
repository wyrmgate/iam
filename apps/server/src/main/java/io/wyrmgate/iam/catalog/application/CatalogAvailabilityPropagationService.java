package io.wyrmgate.iam.catalog.application;

import io.wyrmgate.iam.catalog.domain.CatalogAccessTargetKind;
import io.wyrmgate.iam.catalog.domain.CatalogAvailabilityCauseKind;
import io.wyrmgate.iam.catalog.domain.CatalogAvailabilityPropagation;
import io.wyrmgate.iam.platform.persistence.ClaimedOutboxEvent;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Bounded, replay-safe propagation of Catalog retirement effects to semantic triggers. */
public final class CatalogAvailabilityPropagationService {

    private static final Duration CLAIM_LEASE = Duration.ofSeconds(30);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(5);
    private static final int CLAIM_BATCH = 20;
    private static final int PAGE_SIZE = 200;

    private final JdbcOutboxRepository outbox;
    private final CatalogAvailabilityPropagationRepository propagations;
    private final CatalogAccessTargetAvailabilityFactSink availabilityFacts;
    private final RoleExpansionFactSink roleFacts;
    private final CatalogAvailabilityPropagationWorkSink work;
    private final TransactionExecutor transactions;
    private final Clock clock;

    public CatalogAvailabilityPropagationService(
            JdbcOutboxRepository outbox,
            CatalogAvailabilityPropagationRepository propagations,
            CatalogAccessTargetAvailabilityFactSink availabilityFacts,
            RoleExpansionFactSink roleFacts,
            CatalogAvailabilityPropagationWorkSink work,
            TransactionExecutor transactions) {
        this(
                outbox,
                propagations,
                availabilityFacts,
                roleFacts,
                work,
                transactions,
                Clock.systemUTC());
    }

    CatalogAvailabilityPropagationService(
            JdbcOutboxRepository outbox,
            CatalogAvailabilityPropagationRepository propagations,
            CatalogAccessTargetAvailabilityFactSink availabilityFacts,
            RoleExpansionFactSink roleFacts,
            CatalogAvailabilityPropagationWorkSink work,
            TransactionExecutor transactions,
            Clock clock) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.propagations = Objects.requireNonNull(propagations, "propagations");
        this.availabilityFacts = Objects.requireNonNull(availabilityFacts, "availabilityFacts");
        this.roleFacts = Objects.requireNonNull(roleFacts, "roleFacts");
        this.work = Objects.requireNonNull(work, "work");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public BatchResult processAvailable() {
        Instant now = clock.instant();
        List<ClaimedOutboxEvent> claimed = outbox.claimPending(
                Set.of(CatalogAvailabilityPropagationWorkSink.PROPAGATION_REQUESTED),
                now,
                CLAIM_LEASE,
                CLAIM_BATCH);
        int processed = 0;
        int failed = 0;
        for (ClaimedOutboxEvent item : claimed) {
            try {
                processOne(item, clock.instant());
                outbox.markPublished(
                        item.tenant(), item.event().eventId(), clock.instant());
                processed++;
            } catch (IllegalArgumentException invalid) {
                outbox.markTerminalFailure(
                        item.tenant(),
                        item.event().eventId(),
                        "catalog_availability_propagation_work_invalid");
                failed++;
            } catch (RuntimeException retryable) {
                outbox.markFailed(
                        item.tenant(),
                        item.event().eventId(),
                        clock.instant().plus(RETRY_DELAY),
                        "catalog_availability_propagation_failed");
                failed++;
            }
        }
        return new BatchResult(claimed.size(), processed, failed);
    }

    private void processOne(ClaimedOutboxEvent item, Instant now) {
        var event = item.event();
        if (event.eventVersion() != 1
                || !"catalog-availability-propagation".equals(event.aggregateType())
                || event.aggregateId() == null
                || event.aggregateRevision() == null) {
            throw new IllegalArgumentException("unsupported Catalog availability propagation work");
        }

        CatalogAvailabilityPropagation propagation = propagations.findById(
                        item.tenant(), event.aggregateId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Catalog availability propagation does not exist"));
        if (propagation.state() == CatalogAvailabilityPropagation.State.COMPLETED
                || event.aggregateRevision() < propagation.revision()) {
            return;
        }
        if (event.aggregateRevision() > propagation.revision()) {
            throw new IllegalStateException(
                    "Catalog propagation work is ahead of authoritative process state");
        }

        List<CatalogAvailabilityPropagationRepository.ResourceRef> page =
                propagations.findPhasePage(item.tenant(), propagation, PAGE_SIZE + 1);
        boolean hasMore = page.size() > PAGE_SIZE;
        List<CatalogAvailabilityPropagationRepository.ResourceRef> selected =
                hasMore ? List.copyOf(page.subList(0, PAGE_SIZE)) : List.copyOf(page);

        transactions.required(() -> {
            CatalogAvailabilityPropagation current = propagations.findById(
                            item.tenant(), propagation.id())
                    .orElseThrow();
            if (current.state() == CatalogAvailabilityPropagation.State.COMPLETED
                    || current.revision() != propagation.revision()
                    || current.phase() != propagation.phase()
                    || !Objects.equals(current.afterCreatedAt(), propagation.afterCreatedAt())
                    || !Objects.equals(current.afterResourceId(), propagation.afterResourceId())) {
                return null;
            }

            for (var resource : selected) {
                if (current.phase() == CatalogAvailabilityPropagation.Phase.ENTITLEMENTS) {
                    availabilityFacts.changed(
                            item.tenant(),
                            CatalogAccessTargetKind.ENTITLEMENT,
                            resource.id(),
                            current.causeKind(),
                            current.causeId(),
                            current.causeRevision(),
                            now,
                            event.correlationId(),
                            event.eventId());
                } else if (current.phase() == CatalogAvailabilityPropagation.Phase.ROLES) {
                    availabilityFacts.changed(
                            item.tenant(),
                            CatalogAccessTargetKind.ROLE,
                            resource.id(),
                            current.causeKind(),
                            current.causeId(),
                            current.causeRevision(),
                            now,
                            event.correlationId(),
                            event.eventId());
                } else {
                    roleFacts.changed(
                            item.tenant(),
                            resource.id(),
                            resource.revision(),
                            now,
                            event.correlationId(),
                            event.eventId());
                }
            }

            CatalogAvailabilityPropagation.Phase nextPhase = current.phase();
            Instant afterCreatedAt = selected.isEmpty()
                    ? current.afterCreatedAt()
                    : selected.getLast().createdAt();
            java.util.UUID afterResourceId = selected.isEmpty()
                    ? current.afterResourceId()
                    : selected.getLast().id();
            boolean completed = false;

            if (!hasMore) {
                nextPhase = nextPhase(current.causeKind(), current.phase());
                completed = nextPhase == null;
                if (!completed) {
                    afterCreatedAt = null;
                    afterResourceId = null;
                } else {
                    nextPhase = current.phase();
                }
            }

            CatalogAvailabilityPropagation updated = propagations.recordProgress(
                    item.tenant(),
                    current.id(),
                    nextPhase,
                    afterCreatedAt,
                    afterResourceId,
                    selected.size(),
                    completed,
                    current.revision(),
                    now);
            if (!completed) {
                work.requested(
                        item.tenant(),
                        updated,
                        event.correlationId(),
                        event.eventId());
            }
            return null;
        });
    }

    private static CatalogAvailabilityPropagation.Phase nextPhase(
            CatalogAvailabilityCauseKind causeKind,
            CatalogAvailabilityPropagation.Phase current) {
        return switch (current) {
            case ENTITLEMENTS -> causeKind == CatalogAvailabilityCauseKind.APPLICATION
                    ? CatalogAvailabilityPropagation.Phase.ROLES
                    : CatalogAvailabilityPropagation.Phase.ROLE_EXPANSIONS;
            case ROLES -> CatalogAvailabilityPropagation.Phase.ROLE_EXPANSIONS;
            case ROLE_EXPANSIONS -> null;
        };
    }

    public record BatchResult(int claimed, int processed, int failed) {
        public BatchResult {
            if (claimed < 0 || processed < 0 || failed < 0
                    || processed + failed != claimed) {
                throw new IllegalArgumentException("invalid Catalog propagation batch counts");
            }
        }
    }
}
