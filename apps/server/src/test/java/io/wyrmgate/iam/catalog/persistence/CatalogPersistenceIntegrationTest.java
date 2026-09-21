package io.wyrmgate.iam.catalog.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.wyrmgate.iam.catalog.application.CatalogCommandService;
import io.wyrmgate.iam.catalog.application.CatalogQueryService;
import io.wyrmgate.iam.catalog.domain.CatalogLifecycleState;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.JdbcTenantRepository;
import io.wyrmgate.iam.platform.persistence.SpringTransactionExecutor;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class CatalogPersistenceIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");
    private static final Instant NOW = Instant.parse("2026-09-21T09:00:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static JdbcCatalogRepository repository;
    private static CatalogCommandService commands;
    private static CatalogQueryService queries;

    @BeforeAll
    static void startPostgres() {
        POSTGRES.start();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(dataSource).load();
        flyway.migrate();
        flyway.validate();
        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new JdbcTenantRepository(jdbc, ids);
        repository = new JdbcCatalogRepository(jdbc);
        TransactionExecutor transactions =
                new SpringTransactionExecutor(new DataSourceTransactionManager(dataSource));
        commands = new CatalogCommandService(repository, ids, transactions);
        queries = new CatalogQueryService(repository);
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("16");
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @BeforeEach
    void clearDatabase() {
        jdbc.execute("""
                TRUNCATE TABLE
                    catalog.entitlement,
                    catalog.application_target,
                    catalog.application,
                    platform.tenant
                CASCADE
                """);
    }

    @Test
    void authoritativeCatalogEnforcesTenantAndApplicationBoundaries() {
        TenantContext first = tenant("first");
        TenantContext second = tenant("second");

        var app = commands.createApplication(first, "erp", "ERP", NOW);
        var otherApp = commands.createApplication(first, "hr", "HR", NOW);
        var target = commands.createTarget(first, app.id(), "prod", NOW);
        var entitlement = commands.createEntitlement(
                first, app.id(), target.id(), "finance-read", "group-77", "GROUP", NOW);

        assertThat(queries.findApplication(first, app.id())).contains(app);
        assertThat(queries.findApplication(second, app.id())).isEmpty();
        assertThat(queries.findTarget(second, target.id())).isEmpty();
        assertThat(queries.findEntitlement(second, entitlement.id())).isEmpty();

        assertThatThrownBy(() -> commands.createEntitlement(
                        first, otherApp.id(), target.id(),
                        "bad", null, "GROUP", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another application");

        assertThatThrownBy(() -> jdbc.update("""
                        INSERT INTO catalog.entitlement (
                            id, tenant_id, application_id, application_target_id,
                            code, native_key, entitlement_type, lifecycle_state,
                            revision, created_at, updated_at)
                        VALUES (?, ?, ?, ?, 'bad-direct', NULL, 'GROUP', 'ACTIVE', 1, ?, ?)
                        """,
                        ids.nextId(), first.tenantId(), otherApp.id(), target.id(),
                        Timestamp.from(NOW), Timestamp.from(NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void retirementIsTerminalReadableAndDoesNotCascade() {
        TenantContext tenant = tenant("retirement");
        var app = commands.createApplication(tenant, "erp", "ERP", NOW);
        var target = commands.createTarget(tenant, app.id(), "prod", NOW);
        var entitlement = commands.createEntitlement(
                tenant, app.id(), target.id(), "read", null, "GROUP", NOW);

        var retired = commands.retireApplication(
                tenant, app.id(), app.revision(), NOW.plusSeconds(1));
        assertThat(retired.lifecycleState()).isEqualTo(CatalogLifecycleState.RETIRED);
        assertThat(queries.findTarget(tenant, target.id())).isPresent();
        assertThat(queries.findEntitlement(tenant, entitlement.id())).isPresent();

        assertThatThrownBy(() -> commands.createTarget(
                        tenant, app.id(), "new-target", NOW.plusSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retired");

        assertThatThrownBy(() -> commands.renameApplication(
                        tenant, app.id(), "Should Fail", app.revision(), NOW.plusSeconds(3)))
                .isInstanceOf(StaleWriteException.class);

        var retiredTarget = commands.retireTarget(
                tenant, target.id(), target.revision(), NOW.plusSeconds(4));
        var retiredEntitlement = commands.retireEntitlement(
                tenant, entitlement.id(), entitlement.revision(), NOW.plusSeconds(5));
        assertThat(retiredTarget.lifecycleState()).isEqualTo(CatalogLifecycleState.RETIRED);
        assertThat(retiredEntitlement.lifecycleState()).isEqualTo(CatalogLifecycleState.RETIRED);
    }

    @Test
    void codeUniquenessAndOptimisticRevisionAreEnforced() {
        TenantContext tenant = tenant("revision");
        var app = commands.createApplication(tenant, "erp", "ERP", NOW);

        assertThatThrownBy(() -> commands.createApplication(
                        tenant, "erp", "Duplicate", NOW.plusSeconds(1)))
                .isInstanceOf(DataIntegrityViolationException.class);

        var renamed = commands.renameApplication(
                tenant, app.id(), "ERP v2", 1, NOW.plusSeconds(2));
        assertThat(renamed.revision()).isEqualTo(2);
        assertThat(renamed.name()).isEqualTo("ERP v2");

        assertThatThrownBy(() -> commands.renameApplication(
                        tenant, app.id(), "Stale", 1, NOW.plusSeconds(3)))
                .isInstanceOf(StaleWriteException.class);
    }

    private TenantContext tenant(String name) {
        return new TenantContext(tenants.create(name, NOW).id());
    }
}
