package io.wyrmgate.iam.audit.application;

import io.wyrmgate.iam.audit.domain.AuditRecord;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public final class AuditCommandService implements SecurityAuditPort {
    private final AuditRecordRepository repository;
    private final TransactionExecutor transactions;
    private final Clock clock;

    public AuditCommandService(
            AuditRecordRepository repository,
            TransactionExecutor transactions,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public AuditRecord append(TenantContext tenant, AuditRecordDraft draft) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(draft, "draft");
        return transactions.required(() -> appendInside(tenant, draft));
    }

    private AuditRecord appendInside(TenantContext tenant, AuditRecordDraft draft) {
        var existing = repository.findById(tenant, draft.id());
        if (existing.isPresent()) {
            return requireSame(existing.get(), draft);
        }
        AuditRecord proposed = new AuditRecord(
                draft.id(),
                draft.occurredAt(),
                Instant.now(clock),
                draft.actorId(),
                draft.actionType(),
                draft.resourceType(),
                draft.resourceId(),
                draft.outcome(),
                draft.correlationId(),
                draft.causationId());
        if (repository.insertIfAbsent(tenant, proposed)) {
            return proposed;
        }
        AuditRecord raced = repository.findById(tenant, draft.id())
                .orElseThrow(() -> new AuditRecordConflictException(draft.id()));
        return requireSame(raced, draft);
    }

    private static AuditRecord requireSame(AuditRecord existing, AuditRecordDraft draft) {
        if (!existing.semanticallyEquals(draft)) {
            throw new AuditRecordConflictException(draft.id());
        }
        return existing;
    }
}
