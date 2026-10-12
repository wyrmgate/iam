package io.wyrmgate.iam.platform.persistence;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Process-wide PostgreSQL fixture for ordinary database integration tests.
 *
 * <p>The container and Flyway migration chain are started once. Before each test class, the
 * working database is recreated from a migrated template so classes keep the same clean-database
 * isolation they had with one container per class without replaying every migration.
 *
 * <p>{@link ApplicationFlywayStartupIntegrationTest} intentionally does not use this fixture: it
 * owns the fresh-empty-database migration contract.
 */
public final class SharedPostgresIntegrationDatabase {

    private static final String TEMPLATE_DATABASE = "wyrmgate_test_template";
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");

    static {
        POSTGRES.start();
        Flyway flyway = Flyway.configure().dataSource(dataSource()).load();
        flyway.migrate();
        flyway.validate();
        createTemplateDatabase();
    }

    private SharedPostgresIntegrationDatabase() {}

    public static PostgreSQLContainer container() {
        return POSTGRES;
    }

    public static synchronized void reset() {
        JdbcTemplate admin = adminJdbc();
        String database = POSTGRES.getDatabaseName();
        admin.queryForList(
                "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                        + "WHERE datname = ? AND pid <> pg_backend_pid()",
                Boolean.class,
                database);
        admin.execute("DROP DATABASE " + quoted(database));
        admin.execute("CREATE DATABASE " + quoted(database)
                + " WITH TEMPLATE " + quoted(TEMPLATE_DATABASE));
    }

    public static DataSource dataSource() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    public static JdbcTemplate jdbc() {
        reset();
        return new JdbcTemplate(dataSource());
    }

    private static void createTemplateDatabase() {
        JdbcTemplate admin = adminJdbc();
        admin.execute("DROP DATABASE IF EXISTS " + quoted(TEMPLATE_DATABASE));
        admin.execute("CREATE DATABASE " + quoted(TEMPLATE_DATABASE)
                + " WITH TEMPLATE " + quoted(POSTGRES.getDatabaseName()));
    }

    private static JdbcTemplate adminJdbc() {
        String database = POSTGRES.getDatabaseName();
        String adminUrl = POSTGRES.getJdbcUrl().replace("/" + database, "/postgres");
        return new JdbcTemplate(new DriverManagerDataSource(
                adminUrl, POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private static String quoted(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
