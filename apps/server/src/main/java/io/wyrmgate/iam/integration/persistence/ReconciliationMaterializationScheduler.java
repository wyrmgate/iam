package io.wyrmgate.iam.integration.persistence;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;

public final class ReconciliationMaterializationScheduler {
    private final JdbcIntegrationRuntimeRepository repository;
    private final TransactionExecutor transactions;
    public ReconciliationMaterializationScheduler(JdbcIntegrationRuntimeRepository repository,TransactionExecutor transactions) {
        this.repository=repository; this.transactions=transactions;
    }
    @Scheduled(fixedDelayString="${iam.integration.materialization.poll-interval:PT1S}")
    public void process() {
        for (int i=0;i<10;i++) if (!transactions.required(()->repository.materializeNext(Instant.now()))) break;
    }
}
