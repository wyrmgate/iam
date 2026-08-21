package io.wyrmgate.iam.platform.persistence;

import java.sql.Timestamp;
import java.time.Instant;

/** Narrow JDBC value conversions kept inside the persistence adapter package. */
final class JdbcValues {

    private JdbcValues() {
    }

    static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    static Timestamp nullableTimestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
