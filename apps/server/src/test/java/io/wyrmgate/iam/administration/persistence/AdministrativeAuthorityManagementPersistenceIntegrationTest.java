package io.wyrmgate.iam.administration.persistence;

import static io.wyrmgate.iam.platform.persistence.FlywayTestSupport.assertFullyMigrated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorityException;
import io.wyrmgate.iam.administration.application.AdministrativeAuthorityService;
import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.application.InitialAdminBootstrapService;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.administration.domain.AdministrativeScope;
import io.wyrmgate.iam.administration.domain.ExternalAuthenticationSubject;
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
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Set;
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

class AdministrativeAuthorityManagementPersistenceIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static IdentityCommandService identityCommands;
    private static InitialAdminBootstrapService bootstrap;
    private static AdministrativeAuthorityService authority;
    private static AdministrativeAuthorizationService authorization;

    @BeforeAll
    static void startPostgresAndMigrate() {
        POSTGRES.start();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(dataSource).load();
        flyway.migrate();
        flyway.validate();

        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new JdbcTenantRepository(jdbc, ids);
        JdbcIdentityRepository identities = new JdbcIdentityRepository(jdbc);
        JdbcOutboxRepository outbox = new JdbcOutboxRepository(jdbc);
        TransactionExecutor transactions =
                new SpringTransactionExecutor(new DataSourceTransactionManager(dataSource));
        var governedActors = new IdentityGovernedActorStatusQuery(identities);
        var bindings = new JdbcControlPlaneActorBindingRepository(jdbc);
        authorization = new AdministrativeAuthorizationService(
                new JdbcAdministrativeAuthorizationRepository(jdbc), governedActors);
        bootstrap = new InitialAdminBootstrapService(
                new JdbcInitialAdminBootstrapRepository(jdbc),
                bindings,
                governedActors,
                new JdbcInitialAdminBootstrapFactSink(outbox, ids),
                ids,
                transactions);
        authority = new AdministrativeAuthorityService(
                new JdbcAdministrativeAuthorityRepository(jdbc, ids),
                authorization,
                governedActors,
                ids,
                transactions);
        identityCommands = new IdentityCommandService(
                identities,
                new JdbcIdentityFactSink(outbox, ids),
                ids,
                transactions);

        assertFullyMigrated(flyway);
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @BeforeEach
    void clearRows() {
        jdbc.execute("""
                TRUNCATE TABLE
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
    }

    @Test
    void bootstrapRootIsExplicitlyGrantableAndDelegableWithoutExpandingPermissions() {
        Instant now = Instant.parse("2026-10-01T08:00:00Z");
        TenantContext tenant = tenant("Bootstrap Root", now);
        Admin admin = bootstrapAdmin(tenant, now.plusSeconds(1));

        var root = authority.getGrant(admin.actor(), admin.rootGrantId(), now.plusSeconds(3));
        var role = authority.getRole(admin.actor(), root.roleId(), now.plusSeconds(3));

        assertThat(root.grantable()).isTrue();
        assertThat(root.delegable()).isTrue();
        assertThat(root.authorityBasisGrantId()).isNull();
        assertThat(role.permissions()).containsExactlyInAnyOrderElementsOf(
                AdministrativePermissions.INITIAL_TENANT_ADMIN);
    }

    @Test
    void defaultsDenyManagementAndRejectsForeignTenantBeneficiary() {
        Instant now = Instant.parse("2026-10-01T09:00:00Z");
        TenantContext tenant = tenant("Management Deny", now);
        Identity unprivileged = identity(tenant, IdentityLifecycleState.ACTIVE, now.plusSeconds(1));

        assertThatThrownBy(() -> authority.createRole(
                        new AuthenticatedAdministrativeActor(tenant, unprivileged.id()),
                        "denied-role",
                        "Denied",
                        Set.of(AdministrativePermissions.IDENTITY_READ),
                        now.plusSeconds(2)))
                .isInstanceOf(AdministrativeAuthorityException.class)
                .extracting(e -> ((AdministrativeAuthorityException) e).code())
                .isEqualTo("administration_management_denied");

        TenantContext other = tenant("Foreign", now);
        Identity foreign = identity(other, IdentityLifecycleState.ACTIVE, now.plusSeconds(1));
        Admin admin = bootstrapAdmin(tenant, now.plusSeconds(3));
        var role = authority.createRole(
                admin.actor(),
                "reader",
                "Reader",
                Set.of(AdministrativePermissions.IDENTITY_READ),
                now.plusSeconds(5));

        assertThatThrownBy(() -> authority.createGrant(
                        admin.actor(),
                        foreign.id(),
                        role.id(),
                        AdministrativeScope.global(),
                        null,
                        null,
                        false,
                        false,
                        admin.rootGrantId(),
                        now.plusSeconds(6)))
                .isInstanceOf(AdministrativeAuthorityException.class)
                .extracting(e -> ((AdministrativeAuthorityException) e).code())
                .isEqualTo("beneficiary_not_eligible");
    }

    @Test
    void createsOnlyAuthorityContainedByOneCurrentGrantableBasis() {
        Instant now = Instant.parse("2026-10-01T10:00:00Z");
        TenantContext tenant = tenant("Contained Grant", now);
        Admin admin = bootstrapAdmin(tenant, now.plusSeconds(1));
        Identity beneficiary = identity(tenant, IdentityLifecycleState.ACTIVE, now.plusSeconds(2));
        var role = authority.createRole(
                admin.actor(),
                "identity-reader",
                "Identity Reader",
                Set.of(AdministrativePermissions.IDENTITY_READ),
                now.plusSeconds(3));
        UUID identityResource = ids.nextId();

        var created = authority.createGrant(
                admin.actor(),
                beneficiary.id(),
                role.id(),
                AdministrativeScope.specificResource("identity", identityResource),
                now.plusSeconds(4),
                now.plusSeconds(3600),
                false,
                false,
                admin.rootGrantId(),
                now.plusSeconds(4));

        assertThat(created.actorIdentityId()).isEqualTo(beneficiary.id());
        assertThat(created.scope()).isEqualTo(
                AdministrativeScope.specificResource("identity", identityResource));
        assertThat(created.authorityBasisGrantId()).isEqualTo(admin.rootGrantId());
    }

    @Test
    void deniesPermissionScopeTemporalAndDelegabilityEscalation() {
        Instant now = Instant.parse("2026-10-01T11:00:00Z");
        TenantContext tenant = tenant("Ceilings", now);
        Admin admin = bootstrapAdmin(tenant, now.plusSeconds(1));
        Identity beneficiary = identity(tenant, IdentityLifecycleState.ACTIVE, now.plusSeconds(2));

        var excessiveRole = authority.createRole(
                admin.actor(),
                "catalog-admin",
                "Catalog Admin",
                Set.of(new AdministrativePermission("catalog", "update")),
                now.plusSeconds(3));
        assertCeilingDenied(() -> authority.createGrant(
                admin.actor(),
                beneficiary.id(),
                excessiveRole.id(),
                AdministrativeScope.global(),
                null,
                null,
                false,
                false,
                admin.rootGrantId(),
                now.plusSeconds(4)));

        var reader = authority.createRole(
                admin.actor(),
                "reader",
                "Reader",
                Set.of(AdministrativePermissions.IDENTITY_READ),
                now.plusSeconds(5));
        UUID resourceId = ids.nextId();
        var narrowBasis = authority.createGrant(
                admin.actor(),
                admin.actor().identityId(),
                reader.id(),
                AdministrativeScope.specificResource("identity", resourceId),
                now.plusSeconds(6),
                now.plusSeconds(3600),
                true,
                false,
                admin.rootGrantId(),
                now.plusSeconds(6));

        assertCeilingDenied(() -> authority.createGrant(
                admin.actor(),
                beneficiary.id(),
                reader.id(),
                AdministrativeScope.specificResource("identity", resourceId),
                now.plusSeconds(5),
                now.plusSeconds(1800),
                false,
                false,
                narrowBasis.id(),
                now.plusSeconds(7)));

        assertCeilingDenied(() -> authority.createGrant(
                admin.actor(),
                beneficiary.id(),
                reader.id(),
                AdministrativeScope.global(),
                now.plusSeconds(7),
                now.plusSeconds(1800),
                false,
                false,
                narrowBasis.id(),
                now.plusSeconds(7)));

        assertCeilingDenied(() -> authority.createGrant(
                admin.actor(),
                beneficiary.id(),
                reader.id(),
                AdministrativeScope.specificResource("identity", resourceId),
                now.plusSeconds(7),
                now.plusSeconds(7200),
                false,
                false,
                narrowBasis.id(),
                now.plusSeconds(7)));

        assertCeilingDenied(() -> authority.createGrant(
                admin.actor(),
                beneficiary.id(),
                reader.id(),
                AdministrativeScope.specificResource("identity", resourceId),
                now.plusSeconds(7),
                now.plusSeconds(1800),
                false,
                true,
                narrowBasis.id(),
                now.plusSeconds(7)));
    }

    @Test
    void rolePermissionEditCannotIndirectlyEscalateExistingGrant() {
        Instant now = Instant.parse("2026-10-01T12:00:00Z");
        TenantContext tenant = tenant("Role Edit", now);
        Admin admin = bootstrapAdmin(tenant, now.plusSeconds(1));
        Identity beneficiary = identity(tenant, IdentityLifecycleState.ACTIVE, now.plusSeconds(2));
        var role = authority.createRole(
                admin.actor(),
                "mutable-reader",
                "Mutable Reader",
                Set.of(AdministrativePermissions.IDENTITY_READ),
                now.plusSeconds(3));
        authority.createGrant(
                admin.actor(),
                beneficiary.id(),
                role.id(),
                AdministrativeScope.global(),
                null,
                null,
                false,
                false,
                admin.rootGrantId(),
                now.plusSeconds(4));

        var expandedWithinCeiling = authority.addRolePermission(
                admin.actor(),
                role.id(),
                AdministrativePermissions.IDENTITY_UPDATE,
                role.revision(),
                now.plusSeconds(5));
        assertThat(expandedWithinCeiling.permissions())
                .contains(AdministrativePermissions.IDENTITY_READ, AdministrativePermissions.IDENTITY_UPDATE);

        assertCeilingDenied(() -> authority.addRolePermission(
                admin.actor(),
                role.id(),
                new AdministrativePermission("catalog", "update"),
                expandedWithinCeiling.revision(),
                now.plusSeconds(6)));
    }

    @Test
    void editingBootstrapRoleCannotUseProspectiveAuthorityAsItsOwnBasis() {
        Instant now = Instant.parse("2026-10-01T13:00:00Z");
        TenantContext tenant = tenant("Self Escalation", now);
        Admin admin = bootstrapAdmin(tenant, now.plusSeconds(1));
        var root = authority.getGrant(admin.actor(), admin.rootGrantId(), now.plusSeconds(3));
        var rootRole = authority.getRole(admin.actor(), root.roleId(), now.plusSeconds(3));

        assertCeilingDenied(() -> authority.addRolePermission(
                admin.actor(),
                rootRole.id(),
                new AdministrativePermission("catalog", "update"),
                rootRole.revision(),
                now.plusSeconds(4)));
    }

    @Test
    void revocationAndPermissionRemovalRemainAvailableAsAuthorityReductions() {
        Instant now = Instant.parse("2026-10-01T14:00:00Z");
        TenantContext tenant = tenant("Reduction", now);
        Admin admin = bootstrapAdmin(tenant, now.plusSeconds(1));
        Identity beneficiary = identity(tenant, IdentityLifecycleState.ACTIVE, now.plusSeconds(2));
        var role = authority.createRole(
                admin.actor(),
                "two-permission-reader",
                "Two Permission Reader",
                Set.of(
                        AdministrativePermissions.IDENTITY_READ,
                        AdministrativePermissions.IDENTITY_UPDATE),
                now.plusSeconds(3));
        var grant = authority.createGrant(
                admin.actor(),
                beneficiary.id(),
                role.id(),
                AdministrativeScope.global(),
                null,
                null,
                false,
                false,
                admin.rootGrantId(),
                now.plusSeconds(4));

        var reduced = authority.removeRolePermission(
                admin.actor(),
                role.id(),
                AdministrativePermissions.IDENTITY_UPDATE,
                role.revision(),
                now.plusSeconds(5));
        assertThat(reduced.permissions()).containsExactly(AdministrativePermissions.IDENTITY_READ);

        var revoked = authority.revokeGrant(
                admin.actor(), grant.id(), grant.revision(), now.plusSeconds(6));
        assertThat(revoked.state().name()).isEqualTo("REVOKED");
    }

    @Test
    void staleRevisionAndExpiredOrRevokedBasisFailClosed() {
        Instant now = Instant.parse("2026-10-01T15:00:00Z");
        TenantContext tenant = tenant("Stale And Basis", now);
        Admin admin = bootstrapAdmin(tenant, now.plusSeconds(1));
        Identity beneficiary = identity(tenant, IdentityLifecycleState.ACTIVE, now.plusSeconds(2));
        var role = authority.createRole(
                admin.actor(),
                "reader",
                "Reader",
                Set.of(AdministrativePermissions.IDENTITY_READ),
                now.plusSeconds(3));

        var renamed = authority.renameRole(
                admin.actor(), role.id(), "Renamed", role.revision(), now.plusSeconds(4));
        assertThatThrownBy(() -> authority.renameRole(
                        admin.actor(), role.id(), "Stale", role.revision(), now.plusSeconds(5)))
                .isInstanceOf(StaleWriteException.class);

        var finiteBasis = authority.createGrant(
                admin.actor(),
                admin.actor().identityId(),
                renamed.id(),
                AdministrativeScope.global(),
                now.plusSeconds(6),
                now.plusSeconds(60),
                true,
                false,
                admin.rootGrantId(),
                now.plusSeconds(6));

        assertThatThrownBy(() -> authority.createGrant(
                        admin.actor(),
                        beneficiary.id(),
                        renamed.id(),
                        AdministrativeScope.global(),
                        null,
                        null,
                        false,
                        false,
                        finiteBasis.id(),
                        now.plusSeconds(61)))
                .isInstanceOf(AdministrativeAuthorityException.class)
                .extracting(e -> ((AdministrativeAuthorityException) e).code())
                .isEqualTo("authority_basis_not_effective");

        var anotherBasis = authority.createGrant(
                admin.actor(),
                admin.actor().identityId(),
                renamed.id(),
                AdministrativeScope.global(),
                null,
                null,
                true,
                false,
                admin.rootGrantId(),
                now.plusSeconds(7));
        authority.revokeGrant(
                admin.actor(), anotherBasis.id(), anotherBasis.revision(), now.plusSeconds(8));

        assertThatThrownBy(() -> authority.createGrant(
                        admin.actor(),
                        beneficiary.id(),
                        renamed.id(),
                        AdministrativeScope.global(),
                        null,
                        null,
                        false,
                        false,
                        anotherBasis.id(),
                        now.plusSeconds(9)))
                .isInstanceOf(AdministrativeAuthorityException.class)
                .extracting(e -> ((AdministrativeAuthorityException) e).code())
                .isEqualTo("authority_basis_not_effective");
    }

    @Test
    void createsBoundedDelegationAndAuthorizesActualDelegate() {
        Instant now = Instant.parse("2026-10-01T16:00:00Z");
        TenantContext tenant = tenant("Delegation", now);
        Admin admin = bootstrapAdmin(tenant, now.plusSeconds(1));
        Identity delegate = identity(tenant, IdentityLifecycleState.ACTIVE, now.plusSeconds(2));

        var delegation = authority.createDelegation(
                admin.actor(),
                delegate.id(),
                admin.rootGrantId(),
                AdministrativeScope.global(),
                now.plusSeconds(3),
                now.plusSeconds(300),
                ids.nextId(),
                ids.nextId(),
                now.plusSeconds(3));

        var decision = authorization.authorize(
                new AuthenticatedAdministrativeActor(tenant, delegate.id()),
                AdministrativePermissions.IDENTITY_READ,
                io.wyrmgate.iam.administration.application.AdministrativeResource.collection("identity"),
                now.plusSeconds(4));

        assertThat(decision.allowed()).isTrue();
        assertThat(delegation.delegateIdentityId()).isEqualTo(delegate.id());
        assertThat(delegation.delegatorIdentityId()).isEqualTo(admin.actor().identityId());
        assertThat(delegation.sourceGrantId()).isEqualTo(admin.rootGrantId());
        assertThat(delegation.createdByIdentityId()).isEqualTo(admin.actor().identityId());
    }

    @Test
    void delegationRejectsForeignInactiveNonDelegableAndWidenedAuthority() {
        Instant now = Instant.parse("2026-10-01T17:00:00Z");
        TenantContext tenant = tenant("Delegation Bounds", now);
        Admin admin = bootstrapAdmin(tenant, now.plusSeconds(1));
        Identity active = identity(tenant, IdentityLifecycleState.ACTIVE, now.plusSeconds(2));
        Identity inactive = identity(tenant, IdentityLifecycleState.INACTIVE, now.plusSeconds(2));
        TenantContext other = tenant("Other", now);
        Identity foreign = identity(other, IdentityLifecycleState.ACTIVE, now.plusSeconds(2));

        assertThatThrownBy(() -> authority.createDelegation(
                        admin.actor(), foreign.id(), admin.rootGrantId(),
                        AdministrativeScope.global(), null, now.plusSeconds(300),
                        null, null, now.plusSeconds(3)))
                .isInstanceOf(AdministrativeAuthorityException.class)
                .extracting(e -> ((AdministrativeAuthorityException) e).code())
                .isEqualTo("delegate_not_eligible");

        assertThatThrownBy(() -> authority.createDelegation(
                        admin.actor(), inactive.id(), admin.rootGrantId(),
                        AdministrativeScope.global(), null, now.plusSeconds(300),
                        null, null, now.plusSeconds(3)))
                .isInstanceOf(AdministrativeAuthorityException.class)
                .extracting(e -> ((AdministrativeAuthorityException) e).code())
                .isEqualTo("delegate_not_eligible");

        var readerRole = authority.createRole(
                admin.actor(), "delegation-reader", "Delegation Reader",
                Set.of(AdministrativePermissions.IDENTITY_READ), now.plusSeconds(4));
        UUID resourceId = ids.nextId();
        var nonDelegable = authority.createGrant(
                admin.actor(), admin.actor().identityId(), readerRole.id(),
                AdministrativeScope.specificResource("identity", resourceId),
                now.plusSeconds(5), now.plusSeconds(200),
                true, false, admin.rootGrantId(), now.plusSeconds(5));

        assertThatThrownBy(() -> authority.createDelegation(
                        admin.actor(), active.id(), nonDelegable.id(),
                        AdministrativeScope.specificResource("identity", resourceId),
                        now.plusSeconds(6), now.plusSeconds(100),
                        null, null, now.plusSeconds(6)))
                .isInstanceOf(AdministrativeAuthorityException.class)
                .extracting(e -> ((AdministrativeAuthorityException) e).code())
                .isEqualTo("delegation_source_not_effective");

        var delegable = authority.createGrant(
                admin.actor(), admin.actor().identityId(), readerRole.id(),
                AdministrativeScope.specificResource("identity", resourceId),
                now.plusSeconds(7), now.plusSeconds(200),
                true, true, admin.rootGrantId(), now.plusSeconds(7));

        assertThatThrownBy(() -> authority.createDelegation(
                        admin.actor(), active.id(), delegable.id(),
                        AdministrativeScope.global(),
                        now.plusSeconds(8), now.plusSeconds(100),
                        null, null, now.plusSeconds(8)))
                .isInstanceOf(AdministrativeAuthorityException.class)
                .extracting(e -> ((AdministrativeAuthorityException) e).code())
                .isEqualTo("delegation_ceiling_exceeded");

        assertThatThrownBy(() -> authority.createDelegation(
                        admin.actor(), active.id(), delegable.id(),
                        AdministrativeScope.specificResource("identity", resourceId),
                        now.plusSeconds(6), now.plusSeconds(100),
                        null, null, now.plusSeconds(8)))
                .isInstanceOf(AdministrativeAuthorityException.class)
                .extracting(e -> ((AdministrativeAuthorityException) e).code())
                .isEqualTo("delegation_ceiling_exceeded");

        assertThatThrownBy(() -> authority.createDelegation(
                        admin.actor(), active.id(), delegable.id(),
                        AdministrativeScope.specificResource("identity", resourceId),
                        now.plusSeconds(8), now.plusSeconds(300),
                        null, null, now.plusSeconds(8)))
                .isInstanceOf(AdministrativeAuthorityException.class)
                .extracting(e -> ((AdministrativeAuthorityException) e).code())
                .isEqualTo("delegation_ceiling_exceeded");
    }

    @Test
    void sourceRevocationExpiryAndDelegationRevocationInvalidateAuthorization() {
        Instant now = Instant.parse("2026-10-01T18:00:00Z");
        TenantContext tenant = tenant("Delegation Invalidation", now);
        Admin admin = bootstrapAdmin(tenant, now.plusSeconds(1));
        Identity delegate = identity(tenant, IdentityLifecycleState.ACTIVE, now.plusSeconds(2));
        var readerRole = authority.createRole(
                admin.actor(), "delegated-reader", "Delegated Reader",
                Set.of(AdministrativePermissions.IDENTITY_READ), now.plusSeconds(3));

        var source = authority.createGrant(
                admin.actor(), admin.actor().identityId(), readerRole.id(),
                AdministrativeScope.global(),
                now.plusSeconds(4), now.plusSeconds(120),
                true, true, admin.rootGrantId(), now.plusSeconds(4));
        var delegation = authority.createDelegation(
                admin.actor(), delegate.id(), source.id(),
                AdministrativeScope.global(),
                now.plusSeconds(5), now.plusSeconds(100),
                null, null, now.plusSeconds(5));
        var delegateActor = new AuthenticatedAdministrativeActor(tenant, delegate.id());

        assertThat(authorization.authorize(
                        delegateActor,
                        AdministrativePermissions.IDENTITY_READ,
                        io.wyrmgate.iam.administration.application.AdministrativeResource.collection("identity"),
                        now.plusSeconds(6)).allowed())
                .isTrue();

        authority.revokeGrant(admin.actor(), source.id(), source.revision(), now.plusSeconds(7));

        assertThat(authorization.authorize(
                        delegateActor,
                        AdministrativePermissions.IDENTITY_READ,
                        io.wyrmgate.iam.administration.application.AdministrativeResource.collection("identity"),
                        now.plusSeconds(8)).allowed())
                .isFalse();

        var source2 = authority.createGrant(
                admin.actor(), admin.actor().identityId(), readerRole.id(),
                AdministrativeScope.global(),
                now.plusSeconds(9), now.plusSeconds(40),
                true, true, admin.rootGrantId(), now.plusSeconds(9));
        var delegation2 = authority.createDelegation(
                admin.actor(), delegate.id(), source2.id(),
                AdministrativeScope.global(),
                now.plusSeconds(10), now.plusSeconds(30),
                null, null, now.plusSeconds(10));

        assertThat(authorization.authorize(
                        delegateActor,
                        AdministrativePermissions.IDENTITY_READ,
                        io.wyrmgate.iam.administration.application.AdministrativeResource.collection("identity"),
                        now.plusSeconds(41)).allowed())
                .isFalse();

        var source3 = authority.createGrant(
                admin.actor(), admin.actor().identityId(), readerRole.id(),
                AdministrativeScope.global(),
                now.plusSeconds(42), now.plusSeconds(100),
                true, true, admin.rootGrantId(), now.plusSeconds(42));
        var delegation3 = authority.createDelegation(
                admin.actor(), delegate.id(), source3.id(),
                AdministrativeScope.global(),
                now.plusSeconds(43), now.plusSeconds(90),
                null, null, now.plusSeconds(43));

        var revoked = authority.revokeDelegation(
                admin.actor(), delegation3.id(), delegation3.revision(), now.plusSeconds(44));
        assertThat(revoked.state().name()).isEqualTo("REVOKED");
        assertThat(revoked.revokedByIdentityId()).isEqualTo(admin.actor().identityId());

        assertThatThrownBy(() -> authority.revokeDelegation(
                        admin.actor(), delegation3.id(), delegation3.revision(), now.plusSeconds(45)))
                .isInstanceOf(StaleWriteException.class);
    }

    private static void assertCeilingDenied(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOf(AdministrativeAuthorityException.class)
                .extracting(e -> ((AdministrativeAuthorityException) e).code())
                .isEqualTo("grantability_ceiling_exceeded");
    }

    private static Admin bootstrapAdmin(TenantContext tenant, Instant now) {
        Identity actor = identity(tenant, IdentityLifecycleState.ACTIVE, now);
        var result = bootstrap.bootstrap(
                tenant,
                actor.id(),
                new ExternalAuthenticationSubject(
                        "https://issuer.example/" + tenant.tenantId(),
                        "admin-" + actor.id()),
                now.plusSeconds(1),
                ids.nextId());
        return new Admin(
                new AuthenticatedAdministrativeActor(tenant, actor.id()),
                result.administrativeGrantId());
    }

    private static TenantContext tenant(String name, Instant now) {
        return new TenantContext(tenants.create(name, now).id());
    }

    private static Identity identity(TenantContext tenant, IdentityLifecycleState state, Instant now) {
        return identityCommands.create(
                tenant,
                IdentityType.PERSON,
                new IdentityProfile.PersonProfile(),
                state,
                "Administrative Actor",
                now,
                ids.nextId(),
                null);
    }

    private record Admin(
            AuthenticatedAdministrativeActor actor,
            UUID rootGrantId) {
    }
}
