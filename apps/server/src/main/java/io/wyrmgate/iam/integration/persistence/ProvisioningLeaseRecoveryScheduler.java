package io.wyrmgate.iam.integration.persistence;

import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;

/** Technical timer; Integration owns the task transition and uncertainty semantics. */
public final class ProvisioningLeaseRecoveryScheduler {
    private final JdbcIntegrationRuntimeRepository repository;
    private final TransactionExecutor transactions;
    public ProvisioningLeaseRecoveryScheduler(JdbcIntegrationRuntimeRepository repository, TransactionExecutor transactions) {
        this.repository = repository;
        this.transactions = transactions;
    }
    @Scheduled(fixedDelayString = "${iam.integration.provisioning-recovery.poll-interval:PT1M}")
    public void recover() {
        transactions.required(() -> repository.blockExpiredProvisioning(100, Instant.now()));
        transactions.required(() -> repository.failStaleReconciliation(100, Instant.now()));
    }
}
