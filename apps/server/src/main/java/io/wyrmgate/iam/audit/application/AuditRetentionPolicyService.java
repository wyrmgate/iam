package io.wyrmgate.iam.audit.application;

import io.wyrmgate.iam.audit.domain.AuditRetentionPolicyVersion;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class AuditRetentionPolicyService {

    private final AuditRetentionPolicyRepository repository;
    private final TransactionExecutor transactions;
    private final IdGenerator ids;
    private final Clock clock;

    public AuditRetentionPolicyService(
            AuditRetentionPolicyRepository repository,
            TransactionExecutor transactions,
            IdGenerator ids) {
        this(repository, transactions, ids, Clock.systemUTC());
    }

    AuditRetentionPolicyService(
            AuditRetentionPolicyRepository repository,
            TransactionExecutor transactions,
            IdGenerator ids,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public AuditRetentionPolicyVersion append(
            TenantContext tenant,
            Duration exportArtifactLifetime,
            Duration archiveEligibilityAge,
            Duration minimumOnlineRecordRetention,
            Duration minimumArchiveRetention,
            Instant effectiveFrom,
            UUID correlationId,
            UUID causationId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Instant now = clock.instant();
        return transactions.required(() -> {
            long version = repository.nextVersion(tenant);
            return repository.create(
                    tenant,
                    ids.nextId(),
                    version,
                    exportArtifactLifetime,
                    archiveEligibilityAge,
                    minimumOnlineRecordRetention,
                    minimumArchiveRetention,
                    effectiveFrom,
                    correlationId,
                    causationId,
                    now);
        });
    }

    public AuditRetentionPolicyVersion effective(TenantContext tenant, Instant at) {
        return repository.findEffective(tenant, at)
                .orElseThrow(() -> new IllegalStateException("audit_retention_policy_unavailable"));
    }
}
