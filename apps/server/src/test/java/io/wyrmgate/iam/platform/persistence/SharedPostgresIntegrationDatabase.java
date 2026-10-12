package io.wyrmgate.iam.platform.persistence;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Shared migrated PostgreSQL database for ordinary persistence integration tests.
 *
 * <p>The dedicated application-startup migration test owns fresh-database Flyway verification.
 * Business persistence tests reuse this database and isolate scenarios by truncating their rows.
 */
public final class SharedPostgresIntegrationDatabase {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");
    private static final DataSource DATA_SOURCE;

    static {
        POSTGRES.start();
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        DATA_SOURCE = dataSource;
    }

    private SharedPostgresIntegrationDatabase() {}

    public static DataSource dataSource() {
        return DATA_SOURCE;
    }

    public static JdbcTemplate jdbc() {
        return new JdbcTemplate(DATA_SOURCE);
    }
}
