package io.wyrmgate.iam.administration.persistence;

import static io.wyrmgate.iam.platform.persistence.FlywayTestSupport.assertFullyMigrated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeAuthorityException;
import io.wyrmgate.iam.administration.application.AdministrativeAuthorityService;
import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassAuditSink;
import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassPolicy;
import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassService;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.application.InitialAdminBootstrapService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.domain.AdministrativeAuthoritySource;
import io.wyrmgate.iam.administration.domain.AdministrativeBreakGlassReviewOutcome;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.administration.domain.AdministrativeScope;
import io.wyrmgate.iam.administration.domain.AuthenticationAssuranceContext;
import io.wyrmgate.iam.administration.domain.ExternalAuthenticationSubject;
import io.wyrmgate.iam.audit.application.AuditAdministrativeBreakGlassAuditSink;
import io.wyrmgate.iam.audit.application.AuditCommandService;
import io.wyrmgate.iam.audit.persistence.JdbcAuditRecordRepository;
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
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository;
import io.wyrmgate.iam.platform.persistence.JdbcTenantRepository;
import io.wyrmgate.iam.platform.persistence.SpringTransactionExecutor;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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

class AdministrativeBreakGlassPersistenceIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static IdentityCommandService identityCommands;
    private static InitialAdminBootstrapService bootstrap;
    private static AdministrativeAuthorityService authority;
    private static AdministrativeAuthorizationService authorization;
    private static JdbcAdministrativeAuthorityRepository authorityRepository;
    private static JdbcAdministrativeBreakGlassRepository breakGlassRepository;
    private static JdbcScheduledWorkRepository scheduledWork;
    private static IdentityGovernedActorStatusQuery governedActors;
    private static TransactionExecutor transactions;

    @BeforeAll
    static void start() {
        POSTGRES.start();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(dataSource).load();
        flyway.migrate();
        flyway.validate();
        assertFullyMigrated(flyway);

        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new JdbcTenantRepository(jdbc, ids);
        transactions =
                new SpringTransactionExecutor(new DataSourceTransactionManager(dataSource));
        JdbcIdentityRepository identities = new JdbcIdentityRepository(jdbc);
        JdbcOutboxRepository outbox = new JdbcOutboxRepository(jdbc);
        governedActors = new IdentityGovernedActorStatusQuery(identities);
        authorization = new AdministrativeAuthorizationService(
                new JdbcAdministrativeAuthorizationRepository(jdbc), governedActors);
        authorityRepository = new JdbcAdministrativeAuthorityRepository(jdbc, ids);
        authority = new AdministrativeAuthorityService(
                authorityRepository, authorization, governedActors, ids, transactions);
        breakGlassRepository = new JdbcAdministrativeBreakGlassRepository(jdbc);
        scheduledWork = new JdbcScheduledWorkRepository(jdbc, ids);
        bootstrap = new InitialAdminBootstrapService(
                new JdbcInitialAdminBootstrapRepository(jdbc),
                new JdbcControlPlaneActorBindingRepository(jdbc),
                governedActors,
                new JdbcInitialAdminBootstrapFactSink(outbox, ids),
                ids,
                transactions);
        identityCommands = new IdentityCommandService(
                identities, new JdbcIdentityFactSink(outbox, ids), ids, transactions);
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @BeforeEach
    void reset() {
        jdbc.execute("""
                TRUNCATE TABLE
                    audit.audit_record,
                    administration.administrative_break_glass_obligation,
                    administration.administrative_break_glass_operation,
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
    }

    @Test
    void strongAssuranceCreatesBoundedAuthorityObligationsAndAuditEvidence() {
        Instant now = Instant.parse("2026-10-01T10:00:00Z");
        TenantContext tenant = tenant("Break glass");
        EmergencyActor emergency = emergencyActor(tenant, now);
        var targetRole = authority.createRole(
                emergency.rootActor(),
                "break-glass-reader",
                "Break Glass Reader",
                Set.of(AdministrativePermissions.IDENTITY_READ),
                now.plusSeconds(2));

        var service = service(
                (t, actorId, role, scope, validUntil, at) ->
                        AdministrativeBreakGlassPolicy.Decision.allow(
                                Duration.ofMinutes(10), Duration.ofMinutes(5)),
                realAudit(now));
        var strongActor = emergency.actor().withAssurance(
                AuthenticationAssuranceContext.strong(now, now.plusSeconds(1)));
        UUID correlationId = ids.nextId();
        UUID causationId = ids.nextId();

        var operation = service.activate(
                strongActor,
                targetRole.id(),
                AdministrativeScope.global(),
                now.plusSeconds(300),
                "Production authorization outage",
                "INC-2026-1001",
                correlationId,
                causationId,
                now.plusSeconds(2));

        assertThat(operation.state().name()).isEqualTo("ACTIVE");
        assertThat(operation.reason()).isEqualTo("Production authorization outage");
        assertThat(operation.incidentReference()).isEqualTo("INC-2026-1001");
        assertThat(breakGlassRepository.listObligations(tenant, operation.id()))
                .extracting(o -> o.type().name())
                .containsExactlyInAnyOrder("SECURITY_NOTIFICATION", "POST_USE_REVIEW");

        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM platform.scheduled_work
                        WHERE tenant_id = ?
                          AND handler_type = 'administration.break-glass.security-notification'
                          AND subject_id = ?
                          AND delivery_state = 'READY'
                        """,
                        Integer.class,
                        tenant.tenantId(),
                        operation.id()))
                .isEqualTo(1);

        var decision = authorization.authorize(
                strongActor,
                AdministrativePermissions.IDENTITY_READ,
                AdministrativeResource.collection("identity"),
                now.plusSeconds(3));
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.authoritySource()).isEqualTo(AdministrativeAuthoritySource.BREAK_GLASS);

        var baselineDecision = authorization.authorize(
                emergency.actor(),
                AdministrativePermissions.IDENTITY_READ,
                AdministrativeResource.collection("identity"),
                now.plusSeconds(3));
        assertThat(baselineDecision.allowed()).isFalse();

        assertThat(authorization.authorize(
                        strongActor,
                        AdministrativePermissions.IDENTITY_READ,
                        AdministrativeResource.collection("identity"),
                        now.plusSeconds(300)).allowed())
                .isFalse();

        assertThat(jdbc.queryForObject(
                        """
                        SELECT outcome
                        FROM audit.audit_record
                        WHERE tenant_id = ? AND action_type = 'administration.break-glass.activate'
                        """,
                        String.class,
                        tenant.tenantId()))
                .isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject(
                        """
                        SELECT resource_type
                        FROM audit.audit_record
                        WHERE tenant_id = ? AND action_type = 'administration.break-glass.activate'
                        """,
                        String.class,
                        tenant.tenantId()))
                .isEqualTo("administrative-break-glass-operation");
    }

    @Test
    void baselineAndStaleStrongAssuranceFailClosedAndRecordDeniedAttempts() {
        Instant now = Instant.parse("2026-10-01T11:00:00Z");
        TenantContext tenant = tenant("Assurance denial");
        EmergencyActor emergency = emergencyActor(tenant, now);
        var targetRole = authority.createRole(
                emergency.rootActor(),
                "assurance-reader",
                "Assurance Reader",
                Set.of(AdministrativePermissions.IDENTITY_READ),
                now.plusSeconds(2));
        var service = service(
                (t, actorId, role, scope, validUntil, at) ->
                        AdministrativeBreakGlassPolicy.Decision.allow(
                                Duration.ofMinutes(10), Duration.ofMinutes(2)),
                realAudit(now));

        assertThatThrownBy(() -> service.activate(
                        emergency.actor(),
                        targetRole.id(),
                        AdministrativeScope.global(),
                        now.plusSeconds(120),
                        "Emergency",
                        "INC-BASELINE",
                        ids.nextId(),
                        null,
                        now.plusSeconds(3)))
                .isInstanceOf(AdministrativeAuthorityException.class)
                .extracting(e -> ((AdministrativeAuthorityException) e).code())
                .isEqualTo("break_glass_strong_assurance_required");

        var staleStrong = emergency.actor().withAssurance(
                AuthenticationAssuranceContext.strong(
                        now.minusSeconds(600), now.minusSeconds(600)));
        assertThatThrownBy(() -> service.activate(
                        staleStrong,
                        targetRole.id(),
                        AdministrativeScope.global(),
                        now.plusSeconds(120),
                        "Emergency",
                        "INC-STALE",
                        ids.nextId(),
                        null,
                        now.plusSeconds(4)))
                .isInstanceOf(AdministrativeAuthorityException.class)
                .extracting(e -> ((AdministrativeAuthorityException) e).code())
                .isEqualTo("break_glass_strong_assurance_required");

        Integer denied = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM audit.audit_record
                WHERE tenant_id = ?
                  AND action_type = 'administration.break-glass.activate'
                  AND outcome = 'DENIED'
                """,
                Integer.class,
                tenant.tenantId());
        assertThat(denied).isEqualTo(2);
    }

    @Test
    void unavailablePolicyRecordsFailureAndAuditOutageCannotRewriteSuccess() {
        Instant now = Instant.parse("2026-10-01T12:00:00Z");
        TenantContext tenant = tenant("Break glass failure");
        EmergencyActor emergency = emergencyActor(tenant, now);
        var targetRole = authority.createRole(
                emergency.rootActor(),
                "failure-reader",
                "Failure Reader",
                Set.of(AdministrativePermissions.IDENTITY_READ),
                now.plusSeconds(2));
        var strongActor = emergency.actor().withAssurance(
                AuthenticationAssuranceContext.strong(now, now.plusSeconds(1)));

        var unavailable = service(
                (t, actorId, role, scope, validUntil, at) -> {
                    throw new IllegalStateException("policy backend unavailable");
                },
                realAudit(now));

        assertThatThrownBy(() -> unavailable.activate(
                        strongActor,
                        targetRole.id(),
                        AdministrativeScope.global(),
                        now.plusSeconds(120),
                        "Emergency",
                        "INC-POLICY",
                        ids.nextId(),
                        null,
                        now.plusSeconds(3)))
                .isInstanceOf(AdministrativeAuthorityException.class)
                .extracting(e -> ((AdministrativeAuthorityException) e).code())
                .isEqualTo("break_glass_policy_unavailable");

        assertThat(jdbc.queryForObject(
                        """
                        SELECT outcome
                        FROM audit.audit_record
                        WHERE tenant_id = ? AND action_type = 'administration.break-glass.activate'
                        """,
                        String.class,
                        tenant.tenantId()))
                .isEqualTo("FAILURE");

        AdministrativeBreakGlassAuditSink failingAudit =
                (t, actorId, action, resourceId, outcome, correlation, causation, occurredAt) -> {
                    throw new IllegalStateException("audit unavailable");
                };
        var succeedsWithoutAudit = service(
                (t, actorId, role, scope, validUntil, at) ->
                        AdministrativeBreakGlassPolicy.Decision.allow(
                                Duration.ofMinutes(10), Duration.ofMinutes(5)),
                failingAudit);

        var operation = succeedsWithoutAudit.activate(
                strongActor,
                targetRole.id(),
                AdministrativeScope.global(),
                now.plusSeconds(180),
                "Emergency",
                "INC-AUDIT",
                ids.nextId(),
                null,
                now.plusSeconds(4));
        assertThat(operation.state().name()).isEqualTo("ACTIVE");
    }

    @Test
    void explicitRevocationEndsAuthorityWithoutCompletingObligations() {
        Instant now = Instant.parse("2026-10-01T13:00:00Z");
        TenantContext tenant = tenant("Break glass revoke");
        EmergencyActor emergency = emergencyActor(tenant, now);
        var targetRole = authority.createRole(
                emergency.rootActor(),
                "revoke-reader",
                "Revoke Reader",
                Set.of(AdministrativePermissions.IDENTITY_READ),
                now.plusSeconds(2));
        var service = service(
                (t, actorId, role, scope, validUntil, at) ->
                        AdministrativeBreakGlassPolicy.Decision.allow(
                                Duration.ofMinutes(10), Duration.ofMinutes(5)),
                realAudit(now));
        var strongActor = emergency.actor().withAssurance(
                AuthenticationAssuranceContext.strong(now, now.plusSeconds(1)));

        var operation = service.activate(
                strongActor,
                targetRole.id(),
                AdministrativeScope.global(),
                now.plusSeconds(300),
                "Emergency",
                "INC-REVOKE",
                ids.nextId(),
                null,
                now.plusSeconds(3));
        var revoked = service.revoke(
                emergency.actor(),
                operation.id(),
                operation.revision(),
                ids.nextId(),
                null,
                now.plusSeconds(4));

        assertThat(revoked.state().name()).isEqualTo("REVOKED");
        assertThat(authorization.authorize(
                        strongActor,
                        AdministrativePermissions.IDENTITY_READ,
                        AdministrativeResource.collection("identity"),
                        now.plusSeconds(5)).allowed())
                .isFalse();
        assertThat(breakGlassRepository.listObligations(tenant, operation.id()))
                .allMatch(obligation -> obligation.state().name().equals("PENDING"));

        assertThatThrownBy(() -> service.revoke(
                        emergency.actor(),
                        operation.id(),
                        operation.revision(),
                        null,
                        null,
                        now.plusSeconds(6)))
                .isInstanceOf(io.wyrmgate.iam.platform.persistence.StaleWriteException.class);
    }

    @Test
    void postUseReviewRequiresEndedAuthorityAndIndependentReviewer() {
        Instant now = Instant.parse("2026-10-01T14:00:00Z");
        TenantContext tenant = tenant("Break glass review");
        EmergencyActor emergency = emergencyActor(tenant, now);
        var targetRole = authority.createRole(
                emergency.rootActor(),
                "review-reader",
                "Review Reader",
                Set.of(AdministrativePermissions.IDENTITY_READ),
                now.plusSeconds(2));
        var service = service(
                (t, actorId, role, scope, validUntil, at) ->
                        AdministrativeBreakGlassPolicy.Decision.allow(
                                Duration.ofMinutes(10), Duration.ofMinutes(5)),
                realAudit(now));
        var strongActor = emergency.actor().withAssurance(
                AuthenticationAssuranceContext.strong(now, now.plusSeconds(1)));

        var operation = service.activate(
                strongActor,
                targetRole.id(),
                AdministrativeScope.global(),
                now.plusSeconds(60),
                "Emergency",
                "INC-REVIEW",
                ids.nextId(),
                null,
                now.plusSeconds(3));

        assertThatThrownBy(() -> service.completeReview(
                        emergency.rootActor(),
                        operation.id(),
                        operation.revision(),
                        AdministrativeBreakGlassReviewOutcome.APPROVED_USE,
                        "Reviewed while still active",
                        ids.nextId(),
                        null,
                        now.plusSeconds(30)))
                .isInstanceOf(AdministrativeAuthorityException.class)
                .extracting(e -> ((AdministrativeAuthorityException) e).code())
                .isEqualTo("break_glass_review_not_actionable");

        assertThatThrownBy(() -> service.completeReview(
                        strongActor,
                        operation.id(),
                        operation.revision(),
                        AdministrativeBreakGlassReviewOutcome.APPROVED_USE,
                        "Self review",
                        ids.nextId(),
                        null,
                        now.plusSeconds(61)))
                .isInstanceOf(AdministrativeAuthorityException.class)
                .extracting(e -> ((AdministrativeAuthorityException) e).code())
                .isEqualTo("break_glass_self_review_denied");

        var review = service.completeReview(
                emergency.rootActor(),
                operation.id(),
                operation.revision(),
                AdministrativeBreakGlassReviewOutcome.POLICY_CONCERN,
                "Emergency use was valid but policy follow-up is required.",
                ids.nextId(),
                null,
                now.plusSeconds(61));

        assertThat(review.breakGlassOperationId()).isEqualTo(operation.id());
        assertThat(review.reviewerIdentityId()).isEqualTo(emergency.rootActor().identityId());
        assertThat(review.outcome()).isEqualTo(AdministrativeBreakGlassReviewOutcome.POLICY_CONCERN);
        assertThat(breakGlassRepository.listObligations(tenant, operation.id()))
                .filteredOn(o -> o.type().name().equals("POST_USE_REVIEW"))
                .singleElement()
                .satisfies(o -> {
                    assertThat(o.state().name()).isEqualTo("COMPLETED");
                    assertThat(o.completedAt()).isEqualTo(now.plusSeconds(61));
                });
        assertThat(breakGlassRepository.find(tenant, operation.id()).orElseThrow().revision())
                .isEqualTo(operation.revision() + 1);
    }

    private static AdministrativeBreakGlassService service(
            AdministrativeBreakGlassPolicy policy,
            AdministrativeBreakGlassAuditSink audit) {
        return new AdministrativeBreakGlassService(
                breakGlassRepository,
                authorityRepository,
                authorization,
                governedActors,
                policy,
                audit,
                new JdbcAdministrativeBreakGlassNotificationScheduler(scheduledWork),
                ids,
                transactions);
    }

    private static AdministrativeBreakGlassAuditSink realAudit(Instant now) {
        var repository = new JdbcAuditRecordRepository(jdbc);
        var command = new AuditCommandService(
                repository, transactions, Clock.fixed(now, ZoneOffset.UTC));
        return new AuditAdministrativeBreakGlassAuditSink(command, ids);
    }

    private static EmergencyActor emergencyActor(TenantContext tenant, Instant now) {
        Admin root = bootstrapAdmin(tenant, now);
        Identity emergencyIdentity =
                identity(tenant, IdentityLifecycleState.ACTIVE, now.plusSeconds(1));
        var managerRole = authority.createRole(
                root.actor(),
                "break-glass-manager-" + ids.nextId(),
                "Break Glass Manager",
                Set.of(AdministrativePermissions.MANAGE_AUTHORIZATION),
                now.plusSeconds(1));
        authority.createGrant(
                root.actor(),
                emergencyIdentity.id(),
                managerRole.id(),
                AdministrativeScope.global(),
                null,
                null,
                false,
                false,
                root.rootGrantId(),
                now.plusSeconds(1));
        return new EmergencyActor(
                root.actor(),
                new AuthenticatedAdministrativeActor(tenant, emergencyIdentity.id()));
    }

    private static TenantContext tenant(String name) {
        return new TenantContext(
                tenants.create(name, Instant.parse("2026-10-01T09:00:00Z")).id());
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

    private static Identity identity(
            TenantContext tenant,
            IdentityLifecycleState state,
            Instant now) {
        return identityCommands.create(
                tenant,
                IdentityType.PERSON,
                new IdentityProfile.PersonProfile(),
                state,
                "Break Glass Identity",
                now,
                ids.nextId(),
                null);
    }

    private record Admin(
            AuthenticatedAdministrativeActor actor,
            UUID rootGrantId) {}

    private record EmergencyActor(
            AuthenticatedAdministrativeActor rootActor,
            AuthenticatedAdministrativeActor actor) {}
}
