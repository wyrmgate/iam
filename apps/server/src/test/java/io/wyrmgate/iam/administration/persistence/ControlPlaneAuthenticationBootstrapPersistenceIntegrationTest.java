package io.wyrmgate.iam.administration.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.ControlPlaneActorResolver;
import io.wyrmgate.iam.administration.application.InitialAdminBootstrapNotAllowedException;
import io.wyrmgate.iam.administration.application.InitialAdminBootstrapService;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
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
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
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

class ControlPlaneAuthenticationBootstrapPersistenceIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static JdbcIdentityRepository identities;
    private static IdentityCommandService identityCommands;
    private static JdbcControlPlaneActorBindingRepository bindings;
    private static InitialAdminBootstrapService bootstrap;
    private static ControlPlaneActorResolver actorResolver;
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
        identities = new JdbcIdentityRepository(jdbc);
        JdbcOutboxRepository outbox = new JdbcOutboxRepository(jdbc);
        TransactionExecutor transactions = new SpringTransactionExecutor(new DataSourceTransactionManager(dataSource));
        var governedActors = new IdentityGovernedActorStatusQuery(identities);
        bindings = new JdbcControlPlaneActorBindingRepository(jdbc);
        bootstrap = new InitialAdminBootstrapService(
                new JdbcInitialAdminBootstrapRepository(jdbc),
                bindings,
                governedActors,
                new JdbcInitialAdminBootstrapFactSink(outbox, ids),
                ids,
                transactions);
        actorResolver = new ControlPlaneActorResolver(bindings);
        authorization = new AdministrativeAuthorizationService(
                new JdbcAdministrativeAuthorizationRepository(jdbc), governedActors);
        identityCommands = new IdentityCommandService(
                identities,
                new JdbcIdentityFactSink(outbox, ids),
                ids,
                transactions);

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("22");
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
    void bootstrapsExplicitAuthorityAndResolvesOnlyTheServerSideBoundActor() {
        Instant now = Instant.parse("2026-09-15T10:00:00Z");
        TenantContext tenant = tenant("Bootstrap Tenant", now);
        Identity actor = identity(tenant, IdentityLifecycleState.ACTIVE, now.plusSeconds(1));
        ExternalAuthenticationSubject subject = new ExternalAuthenticationSubject(
                "https://login.example.test/issuer", "operator-123");
        UUID correlationId = ids.nextId();

        var result = bootstrap.bootstrap(tenant, actor.id(), subject, now.plusSeconds(2), correlationId);

        assertThat(count("administration.initial_admin_bootstrap", tenant)).isEqualTo(1);
        assertThat(count("administration.control_plane_actor_binding", tenant)).isEqualTo(1);
        assertThat(count("administration.administrative_role", tenant)).isEqualTo(1);
        assertThat(count("administration.administrative_grant", tenant)).isEqualTo(1);
        assertThat(count("administration.administrative_permission", tenant))
                .isEqualTo(AdministrativePermissions.INITIAL_TENANT_ADMIN.size());

        var resolved = actorResolver.resolve(subject).orElseThrow();
        assertThat(resolved.tenant()).isEqualTo(tenant);
        assertThat(resolved.identityId()).isEqualTo(actor.id());

        var identityRead = authorization.authorize(
                resolved,
                AdministrativePermissions.IDENTITY_READ,
                AdministrativeResource.collection("identity"),
                now.plusSeconds(3));
        var arbitraryCatalogPermission = authorization.authorize(
                resolved,
                new AdministrativePermission("catalog", "update"),
                AdministrativeResource.collection("catalog"),
                now.plusSeconds(3));
        assertThat(identityRead.allowed()).isTrue();
        assertThat(arbitraryCatalogPermission.allowed()).isFalse();

        String payload = jdbc.queryForObject(
                """
                SELECT payload::text FROM platform.outbox_event
                WHERE tenant_id = ? AND event_type = 'administration.initial-admin-bootstrapped'
                """,
                String.class,
                tenant.tenantId());
        assertThat(payload)
                .contains(result.actorBindingId().toString())
                .doesNotContain(subject.issuer())
                .doesNotContain(subject.subject());
        assertThat(result.correlationId()).isEqualTo(correlationId);
    }

    @Test
    void inactiveGovernedIdentityCannotBurnTheBootstrapLatch() {
        Instant now = Instant.parse("2026-09-15T11:00:00Z");
        TenantContext tenant = tenant("Inactive Tenant", now);
        Identity actor = identity(tenant, IdentityLifecycleState.SUSPENDED, now.plusSeconds(1));

        assertThatThrownBy(() -> bootstrap.bootstrap(
                        tenant,
                        actor.id(),
                        new ExternalAuthenticationSubject("https://issuer.example", "suspended"),
                        now.plusSeconds(2),
                        ids.nextId()))
                .isInstanceOf(InitialAdminBootstrapNotAllowedException.class)
                .hasMessageContaining("actor_identity_not_active_in_tenant");
        assertThat(count("administration.initial_admin_bootstrap", tenant)).isZero();
        assertThat(count("administration.control_plane_actor_binding", tenant)).isZero();
    }

    @Test
    void duplicateExternalSubjectRollsBackSecondTenantsBootstrapMarker() {
        Instant now = Instant.parse("2026-09-15T12:00:00Z");
        TenantContext firstTenant = tenant("First Tenant", now);
        TenantContext secondTenant = tenant("Second Tenant", now);
        Identity firstActor = identity(firstTenant, IdentityLifecycleState.ACTIVE, now.plusSeconds(1));
        Identity secondActor = identity(secondTenant, IdentityLifecycleState.ACTIVE, now.plusSeconds(1));
        ExternalAuthenticationSubject subject = new ExternalAuthenticationSubject(
                "https://issuer.example", "same-subject");

        bootstrap.bootstrap(firstTenant, firstActor.id(), subject, now.plusSeconds(2), ids.nextId());
        assertThatThrownBy(() -> bootstrap.bootstrap(
                        secondTenant, secondActor.id(), subject, now.plusSeconds(3), ids.nextId()))
                .isInstanceOf(InitialAdminBootstrapNotAllowedException.class)
                .hasMessageContaining("external_subject_already_bound");

        assertThat(count("administration.initial_admin_bootstrap", secondTenant)).isZero();
        assertThat(count("administration.administrative_grant", secondTenant)).isZero();
        assertThat(actorResolver.resolve(subject).orElseThrow().tenant()).isEqualTo(firstTenant);
    }

    @Test
    void successfulBootstrapNeverReopensAfterAuthorityIsRevoked() {
        Instant now = Instant.parse("2026-09-15T13:00:00Z");
        TenantContext tenant = tenant("Burn Once Tenant", now);
        Identity actor = identity(tenant, IdentityLifecycleState.ACTIVE, now.plusSeconds(1));
        ExternalAuthenticationSubject subject = new ExternalAuthenticationSubject(
                "https://issuer.example", "first-admin");
        var first = bootstrap.bootstrap(tenant, actor.id(), subject, now.plusSeconds(2), ids.nextId());

        jdbc.update(
                """
                UPDATE administration.administrative_grant
                SET state = 'REVOKED', revision = revision + 1, updated_at = ?
                WHERE tenant_id = ? AND id = ?
                """,
                java.sql.Timestamp.from(now.plusSeconds(3)), tenant.tenantId(), first.administrativeGrantId());

        assertThatThrownBy(() -> bootstrap.bootstrap(
                        tenant,
                        actor.id(),
                        new ExternalAuthenticationSubject("https://issuer.example", "replacement-admin"),
                        now.plusSeconds(4),
                        ids.nextId()))
                .isInstanceOf(InitialAdminBootstrapNotAllowedException.class);
        assertThat(count("administration.initial_admin_bootstrap", tenant)).isEqualTo(1);
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

    private static int count(String table, TenantContext tenant) {
        if (!table.matches("administration\\.[a-z_]+")) {
            throw new IllegalArgumentException("unexpected table");
        }
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE tenant_id = ?",
                Integer.class,
                tenant.tenantId());
        return count == null ? 0 : count;
    }
}
