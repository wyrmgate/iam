package io.wyrmgate.iam.platform.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;

public final class FlywayTestSupport {

    private FlywayTestSupport() {}

    public static void assertFullyMigrated(Flyway flyway) {
        flyway.validate();

        MigrationInfo current = flyway.info().current();
        assertThat(current).as("current Flyway migration").isNotNull();
        assertThat(flyway.info().pending()).as("pending Flyway migrations").isEmpty();
    }
}
