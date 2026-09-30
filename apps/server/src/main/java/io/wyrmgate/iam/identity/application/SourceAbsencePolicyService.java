package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.identity.domain.SourceAbsencePolicyVersion;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Identity-owned activation of versioned destructive-absence policy. */
public final class SourceAbsencePolicyService {

    private final SourceCorrelationRepository sources;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;

    public SourceAbsencePolicyService(
            SourceCorrelationRepository sources,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this.sources = Objects.requireNonNull(sources, "sources");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public SourceAbsencePolicyVersion activate(
            TenantContext tenant,
            UUID sourceSystemId,
            int maxInferredTransitions,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(sourceSystemId, "sourceSystemId");
        Objects.requireNonNull(now, "now");
        if (maxInferredTransitions < 1) {
            throw new IllegalArgumentException("maxInferredTransitions must be positive");
        }
        return transactions.required(() -> {
            if (sources.findSourceSystem(tenant, sourceSystemId).isEmpty()) {
                throw new IllegalArgumentException("source system does not exist");
            }
            return sources.replaceActiveAbsencePolicy(
                    tenant,
                    sourceSystemId,
                    maxInferredTransitions,
                    now,
                    ids.nextId());
        });
    }
}
