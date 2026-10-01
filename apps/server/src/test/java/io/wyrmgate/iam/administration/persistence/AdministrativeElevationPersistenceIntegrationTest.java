package io.wyrmgate.iam.administration.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeAuthorityService;
import io.wyrmgate.iam.administration.application.AdministrativeElevationService;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.application.InitialAdminBootstrapService;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.administration.domain.AdministrativeScope;
import io.wyrmgate.iam.administration.domain.ExternalAuthenticationSubject;
import io.wyrmgate.iam.governance.application.ApprovalCaseStartService;
import io.wyrmgate.iam.governance.application.ApprovalCommandException;
import io.wyrmgate.iam.governance.application.ApprovalCommandService;
import io.wyrmgate.iam.governance.application.ApprovalModels.DecisionMode;
import io.wyrmgate.iam.governance.application.ApprovalModels.DecisionValue;
import io.wyrmgate.iam.governance.application.ApprovalModels.PlanSpec;
import io.wyrmgate.iam.governance.application.ApprovalModels.StageSpec;
import io.wyrmgate.iam.governance.application.GovernanceAdministrativeElevationApprovalService;
import io.wyrmgate.iam.governance.persistence.JdbcApprovalRepository;
import io.wyrmgate.iam.identity.application.IdentityCommandService;
import io.wyrmgate.iam.identity.domain.Identity;
import io.wyrmgate.iam.identity.domain.IdentityLifecycleState;
import io.wyrmgate.iam.identity.domain.IdentityProfile;
import io.wyrmgate.iam.identity.domain.IdentityType;
import io.wyrmgate.iam.identity.persistence.IdentityGovernedActorStatusQuery;
import io.wyrmgate.iam.identity.persistence.JdbcIdentityFactSink;
import io.wyrmgate.iam.identity.persistence.JdbcIdentityRepository;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.JdbcTenantRepository;
import io.wyrmgate.iam.platform.persistence.SpringTransactionExecutor;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class AdministrativeElevationPersistenceIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static IdentityCommandService identityCommands;
    private static InitialAdminBootstrapService bootstrap;
    private static AdministrativeAuthorityService authority;
    private static AdministrativeAuthorizationService authorization;
    private static AdministrativeElevationService elevations;
    private static ApprovalCommandService approvalCommands;
    private static AtomicReference<UUID> approver;

    @BeforeAll
    static void start() {
        POSTGRES.start();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(dataSource).load();
        flyway.migrate();
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("50");

        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new JdbcTenantRepository(jdbc, ids);
        TransactionExecutor transactions =
                new SpringTransactionExecutor(new DataSourceTransactionManager(dataSource));
        JdbcIdentityRepository identities = new JdbcIdentityRepository(jdbc);
        JdbcOutboxRepository outbox = new JdbcOutboxRepository(jdbc);
        var governedActors = new IdentityGovernedActorStatusQuery(identities);
        authorization = new AdministrativeAuthorizationService(
                new JdbcAdministrativeAuthorizationRepository(jdbc), governedActors);
        var authorityRepository = new JdbcAdministrativeAuthorityRepository(jdbc, ids);
        authority = new AdministrativeAuthorityService(
                authorityRepository, authorization, governedActors, ids, transactions);
        bootstrap = new InitialAdminBootstrapService(
                new JdbcInitialAdminBootstrapRepository(jdbc),
                new JdbcControlPlaneActorBindingRepository(jdbc),
                governedActors,
                new JdbcInitialAdminBootstrapFactSink(outbox, ids),
                ids,
                transactions);
        identityCommands = new IdentityCommandService(
                identities, new JdbcIdentityFactSink(outbox, ids), ids, transactions);

        JdbcApprovalRepository approvalRepository = new JdbcApprovalRepository(jdbc);
        ApprovalCaseStartService starter =
                new ApprovalCaseStartService(approvalRepository, ids, transactions);
        approver = new AtomicReference<>();
        var approvalBoundary = new GovernanceAdministrativeElevationApprovalService(
                (tenant, elevationId, initiatorIdentityId, beneficiaryIdentityId) ->
                        new PlanSpec(List.of(new StageSpec(
                                DecisionMode.ANY_ONE, List.of(approver.get())))),
                starter,
                approvalRepository);
        approvalCommands = new ApprovalCommandService(
                approvalRepository, (tenant, approvalCase) -> {}, ids, transactions);
        elevations = new AdministrativeElevationService(
                new JdbcAdministrativeElevationRepository(jdbc),
                authorityRepository,
                authorization,
                governedActors,
                approvalBoundary,
                ids,
                transactions);
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @BeforeEach
    void reset() {
        jdbc.execute("""
                TRUNCATE TABLE
                    governance.approval_decision,
                    governance.approval_approver,
                    governance.approval_stage,
                    governance.approval_plan,
                    governance.approval_case,
                    administration.administrative_elevation,
                    administration.initial_admin_bootstrap,
                    administration.control_plane_actor_binding,
                    administration.administrative_delegation,
                    administration.administrative_grant,
                    administration.administrative_role_permission,
                    administration.administrative_role,
                    administration.administrative_permission,
                    platform.scheduled_work,
                    platform.idempotency_record,
                    platform.inbox_message,
                    platform.outbox_event,
                    platform.tenant
                CASCADE
                """);
        approver.set(null);
    }

    @Test
    void approvedMatchingElevationAuthorizesOnlyInsideSemanticValidity() {
        Instant now = Instant.parse("2026-10-01T10:00:00Z");
        TenantContext tenant = tenant("Elevation");
        Admin admin = bootstrapAdmin(tenant, now);
        Identity beneficiary = identity(tenant, IdentityLifecycleState.ACTIVE, now.plusSeconds(1));
        Identity reviewer = identity(tenant, IdentityLifecycleState.ACTIVE, now.plusSeconds(1));
        approver.set(reviewer.id());

        var role = authority.createRole(
                admin.actor(), "jit-reader", "JIT Reader",
                Set.of(AdministrativePermissions.IDENTITY_READ), now.plusSeconds(2));
        var elevation = elevations.request(
                admin.actor(), beneficiary.id(), role.id(), AdministrativeScope.global(),
                now.plusSeconds(5), now.plusSeconds(60), admin.rootGrantId(),
                ids.nextId(), ids.nextId(), now.plusSeconds(3));
        var pending = elevations.requestApproval(
                admin.actor(), elevation.id(), elevation.revision(), now.plusSeconds(4));

        var approved = approvalCommands.decide(
                tenant, pending.approvalCaseId(), reviewer.id(), DecisionValue.APPROVE,
                null, 1, now.plusSeconds(5));
        assertThat(approved.state().name()).isEqualTo("APPROVED");

        var active = elevations.apply(
                admin.actor(), pending.id(), pending.revision(), now.plusSeconds(6));
        assertThat(active.state().name()).isEqualTo("ACTIVE");

        var actor = new AuthenticatedAdministrativeActor(tenant, beneficiary.id());
        assertThat(authorization.authorize(
                        actor,
                        AdministrativePermissions.IDENTITY_READ,
                        io.wyrmgate.iam.administration.application.AdministrativeResource.collection("identity"),
                        now.plusSeconds(7)).allowed())
                .isTrue();
        assertThat(authorization.authorize(
                        actor,
                        AdministrativePermissions.IDENTITY_READ,
                        io.wyrmgate.iam.administration.application.AdministrativeResource.collection("identity"),
                        now.plusSeconds(60)).allowed())
                .isFalse();
    }

    @Test
    void pendingRejectedAndExcessiveElevationNeverAuthorize() {
        Instant now = Instant.parse("2026-10-01T10:30:00Z");
        TenantContext tenant = tenant("Elevation negative");
        Admin admin = bootstrapAdmin(tenant, now);
        Identity beneficiary = identity(tenant, IdentityLifecycleState.ACTIVE, now.plusSeconds(1));
        Identity reviewer = identity(tenant, IdentityLifecycleState.ACTIVE, now.plusSeconds(1));
        approver.set(reviewer.id());

        var role = authority.createRole(
                admin.actor(), "negative-reader", "Negative Reader",
                Set.of(AdministrativePermissions.IDENTITY_READ), now.plusSeconds(2));
        var elevation = elevations.request(
                admin.actor(), beneficiary.id(), role.id(), AdministrativeScope.global(),
                null, now.plusSeconds(60), admin.rootGrantId(), null, null, now.plusSeconds(3));
        var pending = elevations.requestApproval(
                admin.actor(), elevation.id(), elevation.revision(), now.plusSeconds(4));
        var beneficiaryActor = new AuthenticatedAdministrativeActor(tenant, beneficiary.id());

        assertThat(authorization.authorize(
                        beneficiaryActor,
                        AdministrativePermissions.IDENTITY_READ,
                        io.wyrmgate.iam.administration.application.AdministrativeResource.collection("identity"),
                        now.plusSeconds(5)).allowed())
                .isFalse();

        approvalCommands.decide(
                tenant, pending.approvalCaseId(), reviewer.id(), DecisionValue.REJECT,
                "not approved", 1, now.plusSeconds(6));
        var denied = elevations.apply(
                admin.actor(), pending.id(), pending.revision(), now.plusSeconds(7));
        assertThat(denied.state().name()).isEqualTo("DENIED");
        assertThat(authorization.authorize(
                        beneficiaryActor,
                        AdministrativePermissions.IDENTITY_READ,
                        io.wyrmgate.iam.administration.application.AdministrativeResource.collection("identity"),
                        now.plusSeconds(8)).allowed())
                .isFalse();

        var excessiveRole = authority.createRole(
                admin.actor(), "forbidden-elevation", "Forbidden Elevation",
                Set.of(new io.wyrmgate.iam.administration.domain.AdministrativePermission(
                        "catalog", "update")),
                now.plusSeconds(9));
        assertThatThrownBy(() -> elevations.request(
                        admin.actor(), beneficiary.id(), excessiveRole.id(),
                        AdministrativeScope.global(), null, now.plusSeconds(90),
                        admin.rootGrantId(), null, null, now.plusSeconds(10)))
                .isInstanceOf(io.wyrmgate.iam.administration.application.AdministrativeAuthorityException.class)
                .extracting(e -> ((io.wyrmgate.iam.administration.application.AdministrativeAuthorityException) e).code())
                .isEqualTo("elevation_ceiling_exceeded");
    }

    @Test
    void beneficiarySelfApprovalAndStaleApprovedContextFailClosed() {
        Instant now = Instant.parse("2026-10-01T11:00:00Z");
        TenantContext tenant = tenant("Elevation self approval");
        Admin admin = bootstrapAdmin(tenant, now);
        Identity beneficiary = identity(tenant, IdentityLifecycleState.ACTIVE, now.plusSeconds(1));

        var role = authority.createRole(
                admin.actor(), "jit-reader", "JIT Reader",
                Set.of(AdministrativePermissions.IDENTITY_READ), now.plusSeconds(2));
        var elevation = elevations.request(
                admin.actor(), beneficiary.id(), role.id(), AdministrativeScope.global(),
                null, now.plusSeconds(90), admin.rootGrantId(),
                null, null, now.plusSeconds(3));

        approver.set(beneficiary.id());
        assertThatThrownBy(() -> elevations.requestApproval(
                        admin.actor(), elevation.id(), elevation.revision(), now.plusSeconds(4)))
                .isInstanceOf(ApprovalCommandException.class)
                .hasMessageContaining("exclude the initiator and beneficiary");

        Identity reviewer = identity(tenant, IdentityLifecycleState.ACTIVE, now.plusSeconds(4));
        approver.set(reviewer.id());
        var pending = elevations.requestApproval(
                admin.actor(), elevation.id(), elevation.revision(), now.plusSeconds(5));
        approvalCommands.decide(
                tenant, pending.approvalCaseId(), reviewer.id(), DecisionValue.APPROVE,
                null, 1, now.plusSeconds(6));

        var changedRole = authority.addRolePermission(
                admin.actor(), role.id(), AdministrativePermissions.IDENTITY_UPDATE,
                role.revision(), now.plusSeconds(7));
        assertThat(changedRole.revision()).isGreaterThan(role.revision());

        assertThatThrownBy(() -> elevations.apply(
                        admin.actor(), pending.id(), pending.revision(), now.plusSeconds(8)))
                .isInstanceOf(io.wyrmgate.iam.administration.application.AdministrativeAuthorityException.class)
                .extracting(e -> ((io.wyrmgate.iam.administration.application.AdministrativeAuthorityException) e).code())
                .isEqualTo("stale_elevation_context");
    }

    @Test
    void revokedBasisBeforeActivationAndCancellationCannotAuthorize() {
        Instant now = Instant.parse("2026-10-01T12:00:00Z");
        TenantContext tenant = tenant("Elevation basis");
        Admin admin = bootstrapAdmin(tenant, now);
        Identity beneficiary = identity(tenant, IdentityLifecycleState.ACTIVE, now.plusSeconds(1));
        Identity reviewer = identity(tenant, IdentityLifecycleState.ACTIVE, now.plusSeconds(1));
        approver.set(reviewer.id());

        var role = authority.createRole(
                admin.actor(), "bounded-reader", "Bounded Reader",
                Set.of(AdministrativePermissions.IDENTITY_READ), now.plusSeconds(2));
        var basis = authority.createGrant(
                admin.actor(), admin.actor().identityId(), role.id(),
                AdministrativeScope.global(), null, now.plusSeconds(120),
                true, false, admin.rootGrantId(), now.plusSeconds(3));

        var elevation = elevations.request(
                admin.actor(), beneficiary.id(), role.id(), AdministrativeScope.global(),
                null, now.plusSeconds(100), basis.id(), null, null, now.plusSeconds(4));
        var pending = elevations.requestApproval(
                admin.actor(), elevation.id(), elevation.revision(), now.plusSeconds(5));
        approvalCommands.decide(
                tenant, pending.approvalCaseId(), reviewer.id(), DecisionValue.APPROVE,
                null, 1, now.plusSeconds(6));
        authority.revokeGrant(admin.actor(), basis.id(), basis.revision(), now.plusSeconds(7));

        assertThatThrownBy(() -> elevations.apply(
                        admin.actor(), pending.id(), pending.revision(), now.plusSeconds(8)))
                .isInstanceOf(io.wyrmgate.iam.administration.application.AdministrativeAuthorityException.class)
                .extracting(e -> ((io.wyrmgate.iam.administration.application.AdministrativeAuthorityException) e).code())
                .isEqualTo("elevation_ceiling_exceeded");

        var another = elevations.request(
                admin.actor(), beneficiary.id(), role.id(), AdministrativeScope.global(),
                null, now.plusSeconds(100), admin.rootGrantId(), null, null, now.plusSeconds(9));
        var cancelled = elevations.cancel(
                admin.actor(), another.id(), another.revision(), now.plusSeconds(10));
        assertThat(cancelled.state().name()).isEqualTo("CANCELLED");
    }

    private static TenantContext tenant(String name) {
        return new TenantContext(tenants.create(name, Instant.parse("2026-10-01T09:00:00Z")).id());
    }

    private static Admin bootstrapAdmin(TenantContext tenant, Instant now) {
        Identity actor = identity(tenant, IdentityLifecycleState.ACTIVE, now);
        var result = bootstrap.bootstrap(
                tenant,
                actor.id(),
                new ExternalAuthenticationSubject(
                        "https://issuer.example/" + tenant.tenantId(), "admin-" + actor.id()),
                now.plusSeconds(1),
                ids.nextId());
        return new Admin(
                new AuthenticatedAdministrativeActor(tenant, actor.id()),
                result.administrativeGrantId());
    }

    private static Identity identity(TenantContext tenant, IdentityLifecycleState state, Instant now) {
        return identityCommands.create(
                tenant,
                IdentityType.PERSON,
                new IdentityProfile.PersonProfile(),
                state,
                "Elevation Identity",
                now,
                ids.nextId(),
                null);
    }

    private record Admin(AuthenticatedAdministrativeActor actor, UUID rootGrantId) {}
}
