package io.wyrmgate.iam.platform.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "iam.auth.enabled=false",
            "iam.otel.enabled=false"
        })
class ApplicationFlywayStartupIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void applicationStartupMigratesEmptyDatabaseToCurrentVersion() {
        Integer historyTable = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name = 'flyway_schema_history'
                """,
                Integer.class);
        assertThat(historyTable).isEqualTo(1);

        String currentVersion = jdbc.queryForObject(
                """
                SELECT version
                FROM public.flyway_schema_history
                WHERE success
                ORDER BY installed_rank DESC
                LIMIT 1
                """,
                String.class);
        assertThat(currentVersion).isEqualTo("13");

        List<String> schemas = List.of(
                "identity",
                "catalog",
                "access",
                "governance",
                "credential",
                "integration",
                "administration",
                "audit",
                "platform");

        for (String schema : schemas) {
            Integer count = jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.schemata WHERE schema_name = ?",
                    Integer.class,
                    schema);
            assertThat(count).as("schema %s", schema).isEqualTo(1);
        }
    }
}
