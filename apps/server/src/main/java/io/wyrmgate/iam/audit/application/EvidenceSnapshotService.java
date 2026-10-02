package io.wyrmgate.iam.audit.application;

import io.wyrmgate.iam.audit.domain.EvidenceSnapshot;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable ADR-0036 EvidenceSnapshot command/query service. */
public final class EvidenceSnapshotService {

    public static final int MAX_PAGE_SIZE = 200;

    private final EvidenceSnapshotRepository repository;
    private final TransactionExecutor transactions;
    private final Clock clock;

    public EvidenceSnapshotService(
            EvidenceSnapshotRepository repository,
            TransactionExecutor transactions,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public EvidenceSnapshot append(TenantContext tenant, EvidenceSnapshotDraft draft) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(draft, "draft");
        return transactions.required(() -> {
            Optional<EvidenceSnapshot> existing = repository.findById(tenant, draft.id());
            if (existing.isPresent()) return requireSame(existing.get(), draft);

            EvidenceSnapshot proposed = new EvidenceSnapshot(
                    draft.id(),
                    draft.occurredAt(),
                    clock.instant(),
                    draft.actorId(),
                    draft.snapshotType(),
                    draft.subject(),
                    draft.policy(),
                    draft.related(),
                    draft.decisionLabel(),
                    draft.correlationId(),
                    draft.causationId());
            if (repository.insertIfAbsent(tenant, proposed)) return proposed;
            return requireSame(
                    repository.findById(tenant, draft.id())
                            .orElseThrow(() -> new IllegalStateException("EvidenceSnapshot replay conflict")),
                    draft);
        });
    }

    public Optional<EvidenceSnapshot> findById(TenantContext tenant, UUID id) {
        return repository.findById(tenant, id);
    }

    public Page list(
            TenantContext tenant,
            String snapshotType,
            String subjectResourceType,
            UUID subjectResourceId,
            UUID correlationId,
            Position after,
            int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        List<EvidenceSnapshot> fetched = repository.findPage(
                tenant,
                normalized(snapshotType),
                normalized(subjectResourceType),
                subjectResourceId,
                correlationId,
                after == null ? null : after.occurredAt(),
                after == null ? null : after.id(),
                limit + 1);
        boolean more = fetched.size() > limit;
        List<EvidenceSnapshot> items = more
                ? List.copyOf(fetched.subList(0, limit))
                : List.copyOf(fetched);
        Position next = more
                ? new Position(items.get(items.size() - 1).occurredAt(), items.get(items.size() - 1).id())
                : null;
        return new Page(items, next);
    }

    private static EvidenceSnapshot requireSame(
            EvidenceSnapshot existing,
            EvidenceSnapshotDraft draft) {
        if (!existing.id().equals(draft.id())
                || !existing.occurredAt().equals(draft.occurredAt())
                || !Objects.equals(existing.actorId(), draft.actorId())
                || !existing.snapshotType().equals(draft.snapshotType().trim())
                || !existing.subject().equals(draft.subject())
                || !Objects.equals(existing.policy(), draft.policy())
                || !Objects.equals(existing.related(), draft.related())
                || !existing.decisionLabel().equals(draft.decisionLabel().trim())
                || !Objects.equals(existing.correlationId(), draft.correlationId())
                || !Objects.equals(existing.causationId(), draft.causationId())) {
            throw new IllegalStateException("EvidenceSnapshot replay conflict");
        }
        return existing;
    }

    private static String normalized(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > 128) {
            throw new IllegalArgumentException("EvidenceSnapshot filter is invalid");
        }
        return normalized;
    }

    public record Position(Instant occurredAt, UUID id) {
        public Position {
            Objects.requireNonNull(occurredAt, "occurredAt");
            Objects.requireNonNull(id, "id");
        }
    }

    public record Page(List<EvidenceSnapshot> items, Position nextPosition) {
        public Page {
            items = List.copyOf(items);
        }
    }
}
