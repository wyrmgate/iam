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
import io.wyrmgate.iam.tenancy.persistence.TenantTestFixture;
import io.wyrmgate.iam.platform.persistence.SharedPostgresIntegrationDatabase;
import io.wyrmgate.iam.platform.persistence.SpringTransactionExecutor;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class RolePersistenceIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-25T12:00:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static TenantTestFixture tenants;
    private static CatalogCommandService catalog;
    private static RoleCommandService roles;
    private static RoleExpansionQuery expansion;
    private static io.wyrmgate.iam.catalog.application.CatalogAvailabilityPropagationService
            availabilityPropagation;

    @BeforeAll
    static void setUpDatabase() {
        SharedPostgresIntegrationDatabase.reset();
        var dataSource = SharedPostgresIntegrationDatabase.dataSource();
        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new TenantTestFixture(jdbc, ids);
        TransactionExecutor transactions = new SpringTransactionExecutor(
                new DataSourceTransactionManager(dataSource));
        var catalogRepository = new JdbcCatalogRepository(jdbc);
        var roleRepository = new JdbcRoleRepository(jdbc);
        catalog = new CatalogCommandService(
                catalogRepository,
                io.wyrmgate.iam.testsupport.CatalogAccessTestSupport.propagationStarter(
                        jdbc, ids, transactions),
                ids,
                transactions);
        roles = new RoleCommandService(
                catalogRepository,
                roleRepository,
                new JdbcRoleExpansionFactSink(
                        new JdbcOutboxRepository(jdbc), ids),
                io.wyrmgate.iam.testsupport.CatalogAccessTestSupport.propagationStarter(
                        jdbc, ids, transactions),
                ids,
                transactions);
        expansion = new RoleExpansionQueryService(
                catalogRepository, roleRepository);
        availabilityPropagation =
                io.wyrmgate.iam.testsupport.CatalogAccessTestSupport.propagationService(
                        jdbc, ids, transactions);
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
                    tenancy.tenant
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
                "read", NOW);
        var e2 = catalog.createEntitlement(
                tenant, app.id(), target.id(),
                "write", NOW);

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
                "first", NOW);
        var secondEntitlement = catalog.createEntitlement(
                tenant, app.id(), target.id(),
                "second", NOW);

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
        drainAvailabilityPropagation();

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
    void roleTextInvariantsApplyToCreateAndRename() {
        TenantContext tenant = tenant("role-text");

        Role role = roles.createRole(
                tenant,
                Role.RoleType.BUSINESS,
                null,
                "  employee  ",
                "  Employee Access  ",
                NOW);

        assertThat(role.code()).isEqualTo("employee");
        assertThat(role.name()).isEqualTo("Employee Access");

        Role renamed = roles.renameRole(
                tenant, role.id(), "  Workforce Access  ",
                role.revision(), NOW.plusSeconds(1));
        assertThat(renamed.name()).isEqualTo("Workforce Access");

        assertThatThrownBy(() -> roles.createRole(
                        tenant,
                        Role.RoleType.BUSINESS,
                        null,
                        "x".repeat(129),
                        "Too Long",
                        NOW.plusSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum length");

        assertThatThrownBy(() -> roles.renameRole(
                        tenant, role.id(), "x".repeat(513),
                        renamed.revision(), NOW.plusSeconds(3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum length");
    }

    @Test
    void concurrentDraftCreationAllocatesDistinctMonotonicVersionNumbers()
            throws Exception {
        TenantContext tenant = tenant("concurrent-version");
        var app = catalog.createApplication(
                tenant, "app", "App", NOW);
        var target = catalog.createTarget(
                tenant, app.id(), "prod", NOW);
        var entitlement = catalog.createEntitlement(
                tenant, app.id(), target.id(), "read", NOW);
        Role role = roles.createRole(
                tenant,
                Role.RoleType.APPLICATION,
                app.id(),
                "users",
                "Users",
                NOW);

        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return roles.createDraftVersion(
                        tenant,
                        role.id(),
                        List.of(RoleCommandService.MemberSpec.entitlement(
                                entitlement.id())),
                        NOW.plusSeconds(1)).versionNumber();
            });
            var second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return roles.createDraftVersion(
                        tenant,
                        role.id(),
                        List.of(RoleCommandService.MemberSpec.entitlement(
                                entitlement.id())),
                        NOW.plusSeconds(1)).versionNumber();
            });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(1L, 2L);
        } finally {
            executor.shutdownNow();
        }
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
                "foreign", NOW);

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

    @Test
    void expandedCardinalityCountsPathsAndChildChangesCanSuppressAndRestoreParent() {
        TenantContext tenant = tenant("role-bound");
        var app = catalog.createApplication(tenant, "bound", "Bound", NOW);
        var target = catalog.createTarget(tenant, app.id(), "prod", NOW);
        var members = new java.util.ArrayList<RoleCommandService.MemberSpec>();
        for (int i = 0; i < 501; i++) {
            var e = catalog.createEntitlement(tenant, app.id(), target.id(), "e-" + i, NOW);
            members.add(RoleCommandService.MemberSpec.entitlement(e.id()));
        }
        var a = roles.createRole(tenant, Role.RoleType.APPLICATION, app.id(), "a", "A", NOW);
        var b = roles.createRole(tenant, Role.RoleType.APPLICATION, app.id(), "b", "B", NOW);
        var parent = roles.createRole(tenant, Role.RoleType.BUSINESS, null, "p", "Parent", NOW);
        var parentMembers = List.of(RoleCommandService.MemberSpec.applicationRole(a.id()),
                RoleCommandService.MemberSpec.applicationRole(b.id()));
        assertThatThrownBy(() -> roles.createDraftVersion(tenant, parent.id(),
                java.util.Collections.nCopies(1001, members.getFirst()), NOW))
                .hasMessageContaining("1,000 direct members");
        activate(tenant, a.id(), members.subList(0, 500), NOW.plusSeconds(1));
        activate(tenant, b.id(), members.subList(0, 500), NOW.plusSeconds(4));
        activate(tenant, parent.id(), parentMembers, NOW.plusSeconds(7));
        assertThat(expansion.expandCurrent(tenant, parent.id()).paths()).hasSize(1000);

        var pending = roles.createDraftVersion(tenant, parent.id(), parentMembers, NOW.plusSeconds(10));
        roles.markReady(tenant, pending.id(), 1, NOW.plusSeconds(11));
        activate(tenant, b.id(), members, NOW.plusSeconds(12));
        assertThat(expansion.expandCurrent(tenant, parent.id()).status())
                .isEqualTo(RoleExpansionQuery.Status.INVALID);
        assertThat(expansion.expandCurrent(tenant, parent.id()).paths()).isEmpty();
        assertThatThrownBy(() -> roles.activate(tenant, pending.id(), 2, NOW.plusSeconds(15)))
                .hasMessageContaining("1,000 expanded entitlement paths");
        var draft = roles.createDraftVersion(tenant, parent.id(), parentMembers, NOW.plusSeconds(16));
        assertThatThrownBy(() -> roles.markReady(tenant, draft.id(), 1, NOW.plusSeconds(17)))
                .hasMessageContaining("1,000 expanded entitlement paths");
        activate(tenant, b.id(), members.subList(0, 500), NOW.plusSeconds(18));
        assertThat(expansion.expandCurrent(tenant, parent.id()).paths()).hasSize(1000);
    }

    private void drainAvailabilityPropagation() {
        for (int i = 0; i < 20; i++) {
            var batch = availabilityPropagation.processAvailable();
            if (batch.claimed() == 0) {
                return;
            }
        }
        throw new AssertionError("Catalog availability propagation did not drain");
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
                tenants.createActive(name, NOW).id());
    }
}
