package io.wyrmgate.iam.platform.persistence;

import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Spring adapter for the application-facing transaction boundary. */
public final class SpringTransactionExecutor implements TransactionExecutor {

    private final TransactionTemplate transactionTemplate;

    public SpringTransactionExecutor(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    @Override
    public <T> T required(Supplier<T> work) {
        Objects.requireNonNull(work, "work");
        return transactionTemplate.execute(status -> work.get());
    }
}
