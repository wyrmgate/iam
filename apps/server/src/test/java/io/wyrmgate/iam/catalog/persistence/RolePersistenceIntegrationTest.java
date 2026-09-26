package io.wyrmgate.iam.catalog.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.wyrmgate.iam.catalog.application.CatalogCommandService;
import io.wyrmgate.iam.catalog.application.RoleCommandService;
import io.wyrmgate.iam.catalog.application.RoleExpansionQuery;
import io.wyrmgate.iam.catalog.application.RoleExpansionQueryService;
import io.wyrmgate.iam.catalog.domain.Role;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.JdbcTenantRepository;
import io.wyrmgate.iam.platform.persistence.SpringTransactionExecutor;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class RolePersistenceIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");
    private static final Instant NOW =
            Instant.parse("2026-09-25T12:00:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static CatalogCommandService catalog;
    private static RoleCommandService roles;
    private static RoleExpansionQuery expansion;

    @BeforeAll
    static void start() {
        POSTGRES.start();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(dataSource).load();
        flyway.migrate();
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion())
                .isEqualTo("24");

        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new JdbcTenantRepository(jdbc, ids);
        TransactionExecutor transactions = new SpringTransactionExecutor(
                new DataSourceTransactionManager(dataSource));
        var catalogRepository = new JdbcCatalogRepository(jdbc);
        var roleRepository = new JdbcRoleRepository(jdbc);
        catalog = new CatalogCommandService(
                catalogRepository, ids, transactions);
        roles = new RoleCommandService(
                catalogRepository,
                roleRepository,
                new JdbcRoleExpansionFactSink(
                        new JdbcOutboxRepository(jdbc), ids),
                ids,
                transactions);
        expansion = new RoleExpansionQueryService(
                catalogRepository, roleRepository);
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @BeforeEach
    void clear() {
        jdbc.execute("""
                TRUNCATE TABLE
                    catalog.role_version_member,
                    catalog.role_version,
                    catalog.role,
                    catalog.entitlement,
                    catalog.application_target,
                    catalog.application,
                    platform.outbox_event,
                    platform.tenant
                CASCADE
                """);
    }

    @Test
    void applicationRoleActivatesImmutableVersionsAndSupersedesPriorVersion() {
        TenantContext tenant = tenant("application-role");
        var app = catalog.createApplication(
                tenant, "app", "App", NOW);
        var target = catalog.createTarget(
                tenant, app.id(), "prod", NOW);
        var e1 = catalog.createEntitlement(
                tenant, app.id(), target.id(),
                "read", "read", "GROUP", NOW);
        var e2 = catalog.createEntitlement(
                tenant, app.id(), target.id(),
                "write", "write", "GROUP", NOW);

        Role role = roles.createRole(
                tenant,
                Role.RoleType.APPLICATION,
                app.id(),
                "users",
                "Users",
                NOW);

        var v1 = roles.createDraftVersion(
                tenant,
                role.id(),
                List.of(RoleCommandService.MemberSpec.entitlement(e1.id())),
                NOW.plusSeconds(1));
        roles.markReady(tenant, v1.id(), 1, NOW.plusSeconds(2));
        var activeV1 = roles.activate(
                tenant, v1.id(), 2, NOW.plusSeconds(3));

        var first = expansion.expandCurrent(tenant, role.id());
        assertThat(first.status())
                .isEqualTo(RoleExpansionQuery.Status.AVAILABLE);
        assertThat(first.paths()).hasSize(1);
        assertThat(first.paths().getFirst().entitlementId())
                .isEqualTo(e1.id());
        assertThat(first.paths().getFirst().roleVersionPath())
                .containsExactly(v1.id());

        var v2 = roles.createDraftVersion(
                tenant,
                role.id(),
                List.of(RoleCommandService.MemberSpec.entitlement(e2.id())),
                NOW.plusSeconds(4));
        roles.markReady(tenant, v2.id(), 1, NOW.plusSeconds(5));
        roles.activate(tenant, v2.id(), 2, NOW.plusSeconds(6));

        assertThat(jdbc.queryForObject("""
                SELECT state
                FROM catalog.role_version
                WHERE tenant_id = ? AND id = ?
                """, String.class, tenant.tenantId(), activeV1.id()))
                .isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM catalog.role_version
                WHERE tenant_id = ? AND role_id = ? AND state = 'ACTIVE'
                """, Integer.class, tenant.tenantId(), role.id()))
                .isEqualTo(1);

        var second = expansion.expandCurrent(tenant, role.id());
        assertThat(second.paths())
                .extracting(RoleExpansionQuery.EntitlementPath::entitlementId)
                .containsExactly(e2.id());

        assertThatThrownBy(() -> jdbc.update("""
                DELETE FROM catalog.role_version_member
                WHERE tenant_id = ? AND role_version_id = ?
                """, tenant.tenantId(), v2.id()))
                .hasMessageContaining(
                        "activated RoleVersion members are immutable");
    }

    @Test
    void businessRoleExpandsThroughCurrentApplicationRoleVersionAndReceivesParentFact() {
        TenantContext tenant = tenant("business-role");
        var app = catalog.createApplication(
                tenant, "app", "App", NOW);
        var target = catalog.createTarget(
                tenant, app.id(), "prod", NOW);
        var firstEntitlement = catalog.createEntitlement(
                tenant, app.id(), target.id(),
                "first", "first", "GROUP", NOW);
        var secondEntitlement = catalog.createEntitlement(
                tenant, app.id(), target.id(),
                "second", "second", "GROUP", NOW);

        Role applicationRole = roles.createRole(
                tenant,
                Role.RoleType.APPLICATION,
                app.id(),
                "application-users",
                "Application Users",
                NOW);
        var appV1 = activate(
                tenant,
                applicationRole.id(),
                List.of(RoleCommandService.MemberSpec.entitlement(
                        firstEntitlement.id())),
                NOW.plusSeconds(1));

        Role businessRole = roles.createRole(
                tenant,
                Role.RoleType.BUSINESS,
                null,
                "employee",
                "Employee",
                NOW.plusSeconds(4));
        var businessV1 = activate(
                tenant,
                businessRole.id(),
                List.of(RoleCommandService.MemberSpec.applicationRole(
                        applicationRole.id())),
                NOW.plusSeconds(5));

        var first = expansion.expandCurrent(
                tenant, businessRole.id());
        assertThat(first.paths()).hasSize(1);
        assertThat(first.paths().getFirst().entitlementId())
                .isEqualTo(firstEntitlement.id());
        assertThat(first.paths().getFirst().roleVersionPath())
                .containsExactly(businessV1.id(), appV1.id());

        jdbc.execute("TRUNCATE TABLE platform.outbox_event");

        var appV2 = activate(
                tenant,
                applicationRole.id(),
                List.of(RoleCommandService.MemberSpec.entitlement(
                        secondEntitlement.id())),
                NOW.plusSeconds(8));

        var second = expansion.expandCurrent(
                tenant, businessRole.id());
        assertThat(second.paths()).hasSize(1);
        assertThat(second.paths().getFirst().entitlementId())
                .isEqualTo(secondEntitlement.id());
        assertThat(second.paths().getFirst().roleVersionPath())
                .containsExactly(businessV1.id(), appV2.id());

        assertThat(jdbc.queryForList("""
                SELECT aggregate_id
                FROM platform.outbox_event
                WHERE tenant_id = ?
                  AND event_type = 'catalog.role-expansion-changed'
                """, UUID.class, tenant.tenantId()))
                .contains(applicationRole.id(), businessRole.id());
    }

    @Test
    void rejectsCrossApplicationApplicationRoleComposition() {
        TenantContext tenant = tenant("invalid");
        var appOne = catalog.createApplication(
                tenant, "one", "One", NOW);
        var targetOne = catalog.createTarget(
                tenant, appOne.id(), "prod", NOW);
        var appTwo = catalog.createApplication(
                tenant, "two", "Two", NOW);
        var targetTwo = catalog.createTarget(
                tenant, appTwo.id(), "prod", NOW);
        var foreign = catalog.createEntitlement(
                tenant,
                appTwo.id(),
                targetTwo.id(),
                "foreign",
                "foreign",
                "GROUP",
                NOW);

        Role role = roles.createRole(
                tenant,
                Role.RoleType.APPLICATION,
                appOne.id(),
                "users",
                "Users",
                NOW);

        assertThatThrownBy(() -> roles.createDraftVersion(
                tenant,
                role.id(),
                List.of(RoleCommandService.MemberSpec.entitlement(
                        foreign.id())),
                NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another Application");

        assertThat(targetOne.id()).isNotNull();
    }

    private io.wyrmgate.iam.catalog.domain.RoleVersion activate(
            TenantContext tenant,
            UUID roleId,
            List<RoleCommandService.MemberSpec> members,
            Instant at) {
        var version = roles.createDraftVersion(
                tenant, roleId, members, at);
        roles.markReady(tenant, version.id(), 1, at.plusSeconds(1));
        return roles.activate(
                tenant, version.id(), 2, at.plusSeconds(2));
    }

    private TenantContext tenant(String name) {
        return new TenantContext(
                tenants.create(name, NOW).id());
    }
}
