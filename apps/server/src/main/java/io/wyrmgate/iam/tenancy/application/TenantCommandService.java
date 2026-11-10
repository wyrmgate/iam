package io.wyrmgate.iam.tenancy.application;

import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import io.wyrmgate.iam.tenancy.domain.Tenant;
import io.wyrmgate.iam.tenancy.domain.TenantLifecycleAction;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Tenancy-owned semantic commands for authoritative Tenant lifecycle. */
public final class TenantCommandService {

    private final TenantRepository repository;
    private final TenantLifecycleContinuityGuard continuity;
    private final TransactionExecutor transactions;

    public TenantCommandService(
            TenantRepository repository,
            TenantLifecycleContinuityGuard continuity,
            TransactionExecutor transactions) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.continuity = Objects.requireNonNull(continuity, "continuity");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    /**
     * Fresh-install-only creation of the first Tenant. The Tenant starts PROVISIONING because its
     * required owner authority is established later in the bootstrap sequence.
     */
    public Tenant ensureFirstTenant(UUID tenantId, String displayName, Instant now) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(now, "now");
        Tenant desired = Tenant.provisioning(tenantId, displayName, now);

        return transactions.required(() -> {
            Tenant existing = repository.find(tenantId).orElse(null);
            if (existing != null) {
                if (!existing.displayName().equals(desired.displayName())) {
                    throw new IllegalStateException(
                            "preallocated Tenant already exists with a different display name");
                }
                return existing;
            }
            if (repository.count() != 0L) {
                throw new IllegalStateException(
                        "fresh-install bootstrap requires an empty Tenant registry");
            }
            repository.insert(desired);
            return desired;
        });
    }

    public Tenant activate(
            UUID tenantId,
            long expectedRevision,
            Instant now) {
        return transition(
                tenantId,
                expectedRevision,
                now,
                TenantLifecycleAction.ACTIVATE,
                true);
    }

    public Tenant suspend(
            UUID tenantId,
            long expectedRevision,
            Instant now) {
        return transition(
                tenantId,
                expectedRevision,
                now,
                TenantLifecycleAction.SUSPEND,
                false);
    }

    public Tenant resume(
            UUID tenantId,
            long expectedRevision,
            Instant now) {
        return transition(
                tenantId,
                expectedRevision,
                now,
                TenantLifecycleAction.RESUME,
                true);
    }

    public Tenant beginDecommissioning(
            UUID tenantId,
            long expectedRevision,
            Instant now) {
        return transition(
                tenantId,
                expectedRevision,
                now,
                TenantLifecycleAction.BEGIN_DECOMMISSIONING,
                false);
    }

    private Tenant transition(
            UUID tenantId,
            long expectedRevision,
            Instant now,
            TenantLifecycleAction action,
            boolean requiresActiveOwner) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(now, "now");
        if (expectedRevision < 1) {
            throw new IllegalArgumentException("expectedRevision must be positive");
        }

        return transactions.required(() -> {
            Tenant current = repository.find(tenantId)
                    .orElseThrow(() -> new IllegalArgumentException("Tenant does not exist"));
            if (current.revision() != expectedRevision) {
                throw new StaleWriteException("tenant", tenantId, expectedRevision);
            }

            Tenant next = current.transition(action, now);

            TenantContext context = new TenantContext(tenantId);
            if (requiresActiveOwner) {
                continuity.assertMayBecomeActive(context, now);
            } else {
                continuity.assertMaySuspendOrDecommission(context, now);
            }

            return repository.updateLifecycle(next, expectedRevision);
        });
    }
}
