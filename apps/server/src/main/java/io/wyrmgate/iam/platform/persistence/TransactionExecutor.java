package io.wyrmgate.iam.platform.persistence;

import java.util.function.Supplier;

/**
 * Application-facing transaction boundary for bounded authoritative mutations.
 * External/provider calls must not be placed inside work executed here.
 */
public interface TransactionExecutor {

    <T> T required(Supplier<T> work);

    default void required(Runnable work) {
        required(() -> {
            work.run();
            return null;
        });
    }
}
