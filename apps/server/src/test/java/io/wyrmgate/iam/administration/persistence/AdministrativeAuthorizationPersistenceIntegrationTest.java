package io.wyrmgate.iam.administration.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
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
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
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

class AdministrativeAuthorizationPersistenceIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static IdentityCommandService identityCommands;
    private static AdministrativeAuthorizationService authorization;

    @BeforeAll
    static void startPostgresAndMigrateFromEmptyDatabase() {
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
        identityCommands = new IdentityCommandService(
                identities,
                new JdbcIdentityFactSink(new JdbcOutboxRepository(jdbc), ids),
                ids,
                new SpringTransactionExecutor(new DataSourceTransactionManager(dataSource)));
        authorization = new AdministrativeAuthorizationService(
                new JdbcAdministrativeAuthorizationRepository(jdbc),
                new IdentityGovernedActorStatusQuery(identities));

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("24");
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @BeforeEach
    void clearRows() {
        jdbc.execute("""
                TRUNCATE TABLE
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
    void defaultsDenyWithoutAnEffectiveGrant() {
        Instant now = Instant.now();
        TenantContext tenant = tenant("Default Deny", now);
        Identity actor = actor(tenant, IdentityLifecycleState.ACTIVE, now);

        var decision = authorization.authorize(
                new AuthenticatedAdministrativeActor(tenant, actor.id()),
                new AdministrativePermission("identity", "read"),
                AdministrativeResource.collection("identity"),
                now);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.code()).isEqualTo("no_effective_grant");
    }

    @Test
    void globalGrantAllowsMatchingPermissionButNotAnotherAction() {
        Instant now = Instant.now();
        TenantContext tenant = tenant("Global Grant", now);
        Identity actor = actor(tenant, IdentityLifecycleState.ACTIVE, now);
        UUID roleId = roleWithPermission(tenant, "identity", "read", now);
        grant(tenant, actor.id(), roleId, "GLOBAL", null, null, "ACTIVE", null, null, now);

        var allowed = authorization.authorize(
                new AuthenticatedAdministrativeActor(tenant, actor.id()),
                new AdministrativePermission("identity", "read"),
                AdministrativeResource.collection("identity"),
                now);
        var denied = authorization.authorize(
                new AuthenticatedAdministrativeActor(tenant, actor.id()),
                new AdministrativePermission("identity", "update"),
                new AdministrativeResource("identity", ids.nextId()),
                now);

        assertThat(allowed.allowed()).isTrue();
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.code()).isEqualTo("no_effective_grant");
    }

    @Test
    void specificResourceGrantMatchesOnlyTheExactResource() {
        Instant now = Instant.now();
        TenantContext tenant = tenant("Specific Grant", now);
        Identity actor = actor(tenant, IdentityLifecycleState.ACTIVE, now);
        UUID target = ids.nextId();
        UUID roleId = roleWithPermission(tenant, "identity", "update", now);
        grant(tenant, actor.id(), roleId, "SPECIFIC_RESOURCE", "identity", target,
                "ACTIVE", null, null, now);

        assertThat(authorization.authorize(
                        new AuthenticatedAdministrativeActor(tenant, actor.id()),
                        new AdministrativePermission("identity", "update"),
                        new AdministrativeResource("identity", target),
                        now).allowed())
                .isTrue();
        assertThat(authorization.authorize(
                        new AuthenticatedAdministrativeActor(tenant, actor.id()),
                        new AdministrativePermission("identity", "update"),
                        new AdministrativeResource("identity", ids.nextId()),
                        now).allowed())
                .isFalse();
        assertThat(authorization.authorize(
                        new AuthenticatedAdministrativeActor(tenant, actor.id()),
                        new AdministrativePermission("identity", "update"),
                        AdministrativeResource.collection("identity"),
                        now).allowed())
                .isFalse();
    }

    @Test
    void validityStateAndUnsupportedScopeFailClosed() {
        Instant now = Instant.parse("2026-09-15T11:00:00Z");
        TenantContext tenant = tenant("Validity", now);
        Identity actor = actor(tenant, IdentityLifecycleState.ACTIVE, now);
        UUID roleId = roleWithPermission(tenant, "identity", "read", now);

        grant(tenant, actor.id(), roleId, "GLOBAL", null, null,
                "REVOKED", null, null, now.minusSeconds(30));
        grant(tenant, actor.id(), roleId, "GLOBAL", null, null,
                "ACTIVE", now.plusSeconds(60), null, now.minusSeconds(20));
        grant(tenant, actor.id(), roleId, "GLOBAL", null, null,
                "ACTIVE", null, now, now.minusSeconds(10));
        grant(tenant, actor.id(), roleId, "ORGANIZATION", null, ids.nextId(),
                "ACTIVE", null, null, now);

        var decision = authorization.authorize(
                new AuthenticatedAdministrativeActor(tenant, actor.id()),
                new AdministrativePermission("identity", "read"),
                AdministrativeResource.collection("identity"),
                now);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.code()).isEqualTo("no_effective_grant");
    }

    @Test
    void suspendedOrForeignTenantActorCannotUseAStoredGrant() {
        Instant now = Instant.now();
        TenantContext tenant = tenant("Actor State", now);
        TenantContext other = tenant("Other Tenant", now);
        Identity suspended = actor(tenant, IdentityLifecycleState.SUSPENDED, now);
        Identity foreignActor = actor(other, IdentityLifecycleState.ACTIVE, now);
        UUID roleId = roleWithPermission(tenant, "identity", "read", now);
        grant(tenant, suspended.id(), roleId, "GLOBAL", null, null, "ACTIVE", null, null, now);
        grant(tenant, foreignActor.id(), roleId, "GLOBAL", null, null, "ACTIVE", null, null, now);

        assertThat(authorization.authorize(
                        new AuthenticatedAdministrativeActor(tenant, suspended.id()),
                        new AdministrativePermission("identity", "read"),
                        AdministrativeResource.collection("identity"),
                        now).code())
                .isEqualTo("actor_not_eligible");
        assertThat(authorization.authorize(
                        new AuthenticatedAdministrativeActor(tenant, foreignActor.id()),
                        new AdministrativePermission("identity", "read"),
                        AdministrativeResource.collection("identity"),
                        now).code())
                .isEqualTo("actor_not_eligible");
    }

    @Test
    void sameCapabilityReferencesCannotCrossTenantBoundary() {
        Instant now = Instant.now();
        TenantContext first = tenant("First", now);
        TenantContext second = tenant("Second", now);
        Identity actor = actor(second, IdentityLifecycleState.ACTIVE, now);
        UUID firstRole = roleWithPermission(first, "identity", "read", now);

        assertThatThrownBy(() -> grant(
                        second, actor.id(), firstRole, "GLOBAL", null, null,
                        "ACTIVE", null, null, now))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static TenantContext tenant(String name, Instant now) {
        return new TenantContext(tenants.create(name, now).id());
    }

    private static Identity actor(TenantContext tenant, IdentityLifecycleState state, Instant now) {
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

    private static UUID roleWithPermission(
            TenantContext tenant, String resourceType, String action, Instant now) {
        UUID permissionId = ids.nextId();
        UUID roleId = ids.nextId();
        jdbc.update(
                """
                INSERT INTO administration.administrative_permission
                    (id, tenant_id, resource_type, action, created_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                permissionId, tenant.tenantId(), resourceType, action, Timestamp.from(now));
        jdbc.update(
                """
                INSERT INTO administration.administrative_role
                    (id, tenant_id, code, name, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, ?, ?)
                """,
                roleId, tenant.tenantId(), "role-" + roleId, "Test Role",
                Timestamp.from(now), Timestamp.from(now));
        jdbc.update(
                """
                INSERT INTO administration.administrative_role_permission
                    (tenant_id, role_id, permission_id, created_at)
                VALUES (?, ?, ?, ?)
                """,
                tenant.tenantId(), roleId, permissionId, Timestamp.from(now));
        return roleId;
    }

    private static UUID grant(
            TenantContext tenant,
            UUID actorIdentityId,
            UUID roleId,
            String scopeType,
            String scopeResourceType,
            UUID scopeRefId,
            String state,
            Instant validFrom,
            Instant validUntil,
            Instant now) {
        UUID grantId = ids.nextId();
        jdbc.update(
                """
                INSERT INTO administration.administrative_grant (
                    id, tenant_id, actor_identity_id, role_id,
                    scope_type, scope_resource_type, scope_ref_id, state,
                    valid_from, valid_until, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                """,
                grantId,
                tenant.tenantId(),
                actorIdentityId,
                roleId,
                scopeType,
                scopeResourceType,
                scopeRefId,
                state,
                timestamp(validFrom),
                timestamp(validUntil),
                Timestamp.from(now),
                Timestamp.from(now));
        return grantId;
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
