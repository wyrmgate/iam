package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/** Access-owned bounded local admission boundary; no provider calls or human waits. */
public final class AccessAdmissionCoordinator {
    private final LockRepository locks;
    private final TransactionExecutor transactions;

    public AccessAdmissionCoordinator(LockRepository locks, TransactionExecutor transactions) {
        this.locks = Objects.requireNonNull(locks);
        this.transactions = Objects.requireNonNull(transactions);
    }

    public <T> T execute(TenantContext tenant, UUID identityId, Function<Permit,T> work) {
        Objects.requireNonNull(tenant);
        Objects.requireNonNull(identityId);
        Objects.requireNonNull(work);
        return transactions.required(() -> {
            locks.lock(tenant, identityId);
            Permit permit = new Permit(tenant, identityId);
            try {
                return work.apply(permit);
            } finally {
                permit.active = false;
            }
        });
    }

    @FunctionalInterface
    public interface LockRepository {
        void lock(TenantContext tenant, UUID identityId);
    }

    /** Non-transferable process-local scope; it is not a permission or persisted evidence. */
    public static final class Permit {
        private final TenantContext tenant;
        private final UUID identityId;
        private final Thread thread = Thread.currentThread();
        private boolean active = true;

        private Permit(TenantContext tenant, UUID identityId) {
            this.tenant = tenant;
            this.identityId = identityId;
        }

        public void require(TenantContext tenant, UUID identityId) {
            if (!active || thread != Thread.currentThread() || !this.tenant.equals(tenant)
                    || !this.identityId.equals(identityId))
                throw new IllegalStateException("current Access admission permit required");
        }
    }
}
