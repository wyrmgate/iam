package io.wyrmgate.iam.access.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.access.domain.LifecycleAccessPolicyVersion;
import io.wyrmgate.iam.access.persistence.JdbcAccessAssignmentBoundaryScheduler;
import io.wyrmgate.iam.access.persistence.JdbcAccessAssignmentFactSink;
import io.wyrmgate.iam.access.persistence.JdbcAccessAssignmentRepository;
import io.wyrmgate.iam.access.persistence.JdbcLifecycleAccessPolicyRepository;
import io.wyrmgate.iam.catalog.application.CatalogAccessReferenceQuery;
import io.wyrmgate.iam.catalog.application.RoleExpansionQuery;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQuery;
import io.wyrmgate.iam.identity.application.IdentityLifecycleAccessQuery;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository;
import io.wyrmgate.iam.platform.persistence.JdbcTenantRepository;
import io.wyrmgate.iam.platform.persistence.SpringTransactionExecutor;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
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

class LifecycleAccessReconciliationIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");
    private static final Instant NOW = Instant.parse("2026-09-30T06:30:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static JdbcOutboxRepository outbox;
    private static JdbcAccessAssignmentRepository assignments;
    private static JdbcLifecycleAccessPolicyRepository policies;
    private static LifecycleAccessPolicyService policyService;
    private static AccessAssignmentCommandService commands;
    private static LifecycleAccessReconciliationService reconciler;
    private static MutableIdentityPolicyQuery identityPolicy;
    private static MutableGuard guard;
    private static MutableApprovalCommand approval;
    private static UUID applicationTargetId;
    private static UUID roleVersionId;

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
        outbox = new JdbcOutboxRepository(jdbc);
        assignments = new JdbcAccessAssignmentRepository(jdbc);
        policies = new JdbcLifecycleAccessPolicyRepository(jdbc);
        TransactionExecutor transactions =
                new SpringTransactionExecutor(new DataSourceTransactionManager(dataSource));
        identityPolicy = new MutableIdentityPolicyQuery();
        guard = new MutableGuard();
        approval = new MutableApprovalCommand();
        applicationTargetId = ids.nextId();
        roleVersionId = ids.nextId();

        CatalogAccessReferenceQuery catalog =
                (tenant, entitlementId) ->
                        CatalogAccessReferenceQuery.EntitlementReference.valid(applicationTargetId);
        RoleExpansionQuery roles =
                (tenant, roleId) -> RoleExpansionQuery.Result.available(
                        roleId,
                        java.util.List.of(new RoleExpansionQuery.EntitlementPath(
                                ids.nextId(), applicationTargetId, java.util.List.of(roleVersionId))));
        IdentityAccessReferenceQuery identityReferences = new IdentityAccessReferenceQuery() {
            @Override
            public boolean identityExists(TenantContext tenant, UUID identityId) {
                return identityPolicy.contexts.containsKey(identityId);
            }

            @Override
            public IdentityAccessStatus accessStatus(TenantContext tenant, UUID identityId) {
                var context = identityPolicy.contexts.get(identityId);
                if (context == null) return IdentityAccessStatus.notFound();
                return "ACTIVE".equals(context.lifecycleState())
                        ? IdentityAccessStatus.eligible(context.lifecycleState(), context.identityRevision())
                        : IdentityAccessStatus.ineligible(context.lifecycleState(), context.identityRevision());
            }

            @Override
            public PrincipalReference principal(TenantContext tenant, UUID principalId) {
                return PrincipalReference.notFound();
            }

            @Override
            public PrincipalSelection selectUniqueActivePrincipal(
                    TenantContext tenant, UUID identityId, UUID targetId) {
                return PrincipalSelection.none();
            }
        };

        commands = new AccessAssignmentCommandService(
                assignments,
                identityReferences,
                catalog,
                roles,
                new JdbcAccessAssignmentFactSink(outbox, ids),
                new JdbcAccessAssignmentBoundaryScheduler(
                        new JdbcScheduledWorkRepository(jdbc, ids)),
                ids,
                transactions);
        policyService = new LifecycleAccessPolicyService(
                policies, identityPolicy, catalog, roles, ids, transactions);
        reconciler = new LifecycleAccessReconciliationService(
                outbox, policies, assignments, commands, identityPolicy, guard, approval);

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("44");
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @BeforeEach
    void clearRows() {
        jdbc.execute("""
                TRUNCATE TABLE
                    platform.scheduled_work,
                    platform.idempotency_record,
                    platform.inbox_message,
                    platform.outbox_event,
                    platform.tenant
                CASCADE
                """);
        identityPolicy.contexts.clear();
        guard.decision = LifecycleAccessPrivilegeGuard.Result.authorize();
        guard.calls = 0;
        approval.satisfied = false;
        approval.requests = 0;
    }

    @Test
    void activeJoinerAlwaysRuleCreatesOneAssignmentAndReplayIsIdempotent() {
        TenantContext tenant = tenant("Joiner");
        UUID identityId = activeIdentity();
        UUID entitlementId = ids.nextId();
        UUID ruleId = ids.nextId();
        policyService.activate(
                tenant,
                java.util.List.of(alwaysEntitlement(ruleId, entitlementId)),
                NOW.plusSeconds(1));

        reconciler.reconcileEvent(tenant, identityId, NOW.plusSeconds(2));
        AccessAssignment first = assignments.findCurrentLifecyclePolicyAssignment(
                tenant, identityId, ruleId).orElseThrow();

        reconciler.reconcileEvent(tenant, identityId, NOW.plusSeconds(3));
        AccessAssignment replay = assignments.findCurrentLifecyclePolicyAssignment(
                tenant, identityId, ruleId).orElseThrow();

        assertThat(first.id()).isEqualTo(replay.id());
        assertThat(first.provenanceKind())
                .isEqualTo(AccessAssignment.ProvenanceKind.LIFECYCLE_POLICY_RULE);
        assertThat(first.provenanceRefId()).isEqualTo(ruleId);
        assertThat(guard.calls).isEqualTo(1);
        assertThat(currentPolicyAssignmentCount(tenant, identityId)).isEqualTo(1);
    }

    @Test
    void canonicalMoverMismatchRemovesPolicyAccessEvenWhenGovernanceUnavailable() {
        TenantContext tenant = tenant("Mover reduction");
        UUID identityId = activeIdentity();
        identityPolicy.setString(identityId, "department", "ENG", 1);
        UUID entitlementId = ids.nextId();
        UUID ruleId = ids.nextId();
        policyService.activate(
                tenant,
                java.util.List.of(exactEntitlement(
                        ruleId, "department", "ENG", entitlementId)),
                NOW.plusSeconds(1));

        reconciler.reconcileEvent(tenant, identityId, NOW.plusSeconds(2));
        AccessAssignment created = assignments.findCurrentLifecyclePolicyAssignment(
                tenant, identityId, ruleId).orElseThrow();

        guard.decision = LifecycleAccessPrivilegeGuard.Result.unavailable("outage");
        identityPolicy.setString(identityId, "department", "SALES", 2);
        reconciler.reconcileEvent(tenant, identityId, NOW.plusSeconds(3));

        assertThat(assignments.findCurrentLifecyclePolicyAssignment(
                tenant, identityId, ruleId)).isEmpty();
        assertThat(assignments.findById(tenant, created.id()).orElseThrow().lifecycleState())
                .isEqualTo(AccessAssignment.LifecycleState.REVOKED);
        assertThat(guard.calls).isEqualTo(1);
    }

    @Test
    void sameLogicalRuleTargetChangeRemovesOldBeforeFailClosedIncrease() {
        TenantContext tenant = tenant("Target change");
        UUID identityId = activeIdentity();
        UUID ruleId = ids.nextId();
        UUID oldEntitlement = ids.nextId();
        UUID newEntitlement = ids.nextId();

        policyService.activate(
                tenant,
                java.util.List.of(alwaysEntitlement(ruleId, oldEntitlement)),
                NOW.plusSeconds(1));
        reconciler.reconcileEvent(tenant, identityId, NOW.plusSeconds(2));
        UUID oldAssignmentId = assignments.findCurrentLifecyclePolicyAssignment(
                tenant, identityId, ruleId).orElseThrow().id();

        policyService.activate(
                tenant,
                java.util.List.of(alwaysEntitlement(ruleId, newEntitlement)),
                NOW.plusSeconds(3));
        guard.decision = LifecycleAccessPrivilegeGuard.Result.unavailable("governance_down");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                reconciler.reconcileEvent(tenant, identityId, NOW.plusSeconds(4)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(assignments.findCurrentLifecyclePolicyAssignment(
                tenant, identityId, ruleId)).isEmpty();
        assertThat(assignments.findById(tenant, oldAssignmentId).orElseThrow().lifecycleState())
                .isEqualTo(AccessAssignment.LifecycleState.REVOKED);

        guard.decision = LifecycleAccessPrivilegeGuard.Result.authorize();
        reconciler.reconcileEvent(tenant, identityId, NOW.plusSeconds(5));
        AccessAssignment replacement = assignments.findCurrentLifecyclePolicyAssignment(
                tenant, identityId, ruleId).orElseThrow();
        assertThat(replacement.entitlementId()).isEqualTo(newEntitlement);
        assertThat(replacement.id()).isNotEqualTo(oldAssignmentId);
    }

    @Test
    void nonActiveIdentityAndNonAuthorizingGuardDoNotGrant() {
        TenantContext tenant = tenant("Fail closed");
        UUID pendingId = ids.nextId();
        identityPolicy.setContext(pendingId, "PENDING", 1);
        UUID entitlementId = ids.nextId();
        UUID ruleId = ids.nextId();
        policyService.activate(
                tenant,
                java.util.List.of(alwaysEntitlement(ruleId, entitlementId)),
                NOW.plusSeconds(1));

        reconciler.reconcileEvent(tenant, pendingId, NOW.plusSeconds(2));
        assertThat(assignments.findCurrentLifecyclePolicyAssignment(
                tenant, pendingId, ruleId)).isEmpty();
        assertThat(guard.calls).isZero();

        UUID activeId = activeIdentity();
        guard.decision = LifecycleAccessPrivilegeGuard.Result.requireApproval();
        reconciler.reconcileEvent(tenant, activeId, NOW.plusSeconds(3));
        assertThat(assignments.findCurrentLifecyclePolicyAssignment(
                tenant, activeId, ruleId)).isEmpty();

        guard.decision = LifecycleAccessPrivilegeGuard.Result.deny();
        reconciler.reconcileEvent(tenant, activeId, NOW.plusSeconds(4));
        assertThat(assignments.findCurrentLifecyclePolicyAssignment(
                tenant, activeId, ruleId)).isEmpty();
    }

    @Test
    void approvalRequiredStaysAbsentUntilCurrentApprovalIsSatisfied() {
        TenantContext tenant = tenant("Approval gate");
        UUID identityId = activeIdentity();
        UUID entitlementId = ids.nextId();
        UUID ruleId = ids.nextId();
        policyService.activate(
                tenant,
                java.util.List.of(alwaysEntitlement(ruleId, entitlementId)),
                NOW.plusSeconds(1));

        guard.decision = LifecycleAccessPrivilegeGuard.Result.requireApproval();
        reconciler.reconcileEvent(tenant, identityId, NOW.plusSeconds(2));

        assertThat(assignments.findCurrentLifecyclePolicyAssignment(
                tenant, identityId, ruleId)).isEmpty();
        assertThat(approval.requests).isEqualTo(1);

        approval.satisfied = true;
        reconciler.reconcileEvent(tenant, identityId, NOW.plusSeconds(3));

        AccessAssignment granted = assignments.findCurrentLifecyclePolicyAssignment(
                tenant, identityId, ruleId).orElseThrow();
        assertThat(granted.entitlementId()).isEqualTo(entitlementId);
        assertThat(approval.requests).isEqualTo(1);
    }

    @Test
    void roleTargetIsSupportedAndManualAssignmentIsUntouched() {
        TenantContext tenant = tenant("Role and manual");
        UUID identityId = activeIdentity();
        UUID manualEntitlement = ids.nextId();
        AccessAssignment manual = commands.createEntitlementAssignment(
                tenant,
                identityId,
                manualEntitlement,
                AccessAssignment.PrincipalConstraintKind.ANY,
                null,
                null,
                null,
                NOW.plusSeconds(1));

        UUID roleId = ids.nextId();
        UUID ruleId = ids.nextId();
        policyService.activate(
                tenant,
                java.util.List.of(new LifecycleAccessPolicyVersion.Rule(
                        ruleId,
                        LifecycleAccessPolicyVersion.PredicateKind.ALWAYS,
                        null,
                        null,
                        AccessAssignment.TargetKind.ROLE,
                        roleId)),
                NOW.plusSeconds(2));

        reconciler.reconcileEvent(tenant, identityId, NOW.plusSeconds(3));
        AccessAssignment roleAssignment = assignments.findCurrentLifecyclePolicyAssignment(
                tenant, identityId, ruleId).orElseThrow();
        assertThat(roleAssignment.targetKind()).isEqualTo(AccessAssignment.TargetKind.ROLE);
        assertThat(roleAssignment.roleId()).isEqualTo(roleId);

        identityPolicy.setContext(identityId, "INACTIVE", 2);
        reconciler.reconcileEvent(tenant, identityId, NOW.plusSeconds(4));

        assertThat(assignments.findCurrentLifecyclePolicyAssignment(
                tenant, identityId, ruleId)).isEmpty();
        assertThat(assignments.findById(tenant, manual.id()).orElseThrow().lifecycleState())
                .isEqualTo(AccessAssignment.LifecycleState.ACTIVE);
    }

    private static TenantContext tenant(String name) {
        return new TenantContext(tenants.create(name, NOW).id());
    }

    private static UUID activeIdentity() {
        UUID id = ids.nextId();
        identityPolicy.setContext(id, "ACTIVE", 1);
        return id;
    }

    private static LifecycleAccessPolicyVersion.Rule alwaysEntitlement(
            UUID ruleId, UUID entitlementId) {
        return new LifecycleAccessPolicyVersion.Rule(
                ruleId,
                LifecycleAccessPolicyVersion.PredicateKind.ALWAYS,
                null,
                null,
                null,
                null,
                null,
                AccessAssignment.TargetKind.ENTITLEMENT,
                entitlementId);
    }

    private static LifecycleAccessPolicyVersion.Rule exactEntitlement(
            UUID ruleId, String key, String value, UUID entitlementId) {
        return new LifecycleAccessPolicyVersion.Rule(
                ruleId,
                LifecycleAccessPolicyVersion.PredicateKind.CANONICAL_STRING_EQUALS,
                key,
                value,
                null,
                null,
                null,
                AccessAssignment.TargetKind.ENTITLEMENT,
                entitlementId);
    }

    private static long currentPolicyAssignmentCount(
            TenantContext tenant, UUID identityId) {
        Long count = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM access.access_assignment
                WHERE tenant_id = ? AND identity_id = ?
                  AND provenance_kind = 'LIFECYCLE_POLICY_RULE'
                  AND lifecycle_state IN ('SCHEDULED','ACTIVE','SUSPENDED')
                """,
                Long.class,
                tenant.tenantId(),
                identityId);
        return count == null ? 0 : count;
    }

    private static final class MutableApprovalCommand implements LifecycleAccessApprovalCommand {
        private boolean satisfied;
        private int requests;

        @Override
        public boolean currentApprovalSatisfied(
                TenantContext tenant, UUID identityId, UUID lifecycleRuleId,
                AccessAssignment.TargetKind targetKind, UUID targetId) {
            return satisfied;
        }

        @Override
        public void requestApproval(
                TenantContext tenant, UUID identityId, UUID lifecycleRuleId,
                AccessAssignment.TargetKind targetKind, UUID targetId, Instant at,
                UUID correlationId, UUID causationId) {
            requests++;
        }
    }

    private static final class MutableGuard implements LifecycleAccessPrivilegeGuard {
        private Result decision = Result.authorize();
        private int calls;

        @Override
        public Result evaluate(
                TenantContext tenant,
                UUID identityId,
                UUID lifecycleRuleId,
                AccessAssignment.TargetKind targetKind,
                UUID targetId,
                Instant at,
                UUID correlationId,
                UUID causationId) {
            calls++;
            return decision;
        }
    }

    private static final class MutableIdentityPolicyQuery
            implements IdentityLifecycleAccessQuery {
        private final Map<UUID, Context> contexts = new LinkedHashMap<>();

        @Override
        public boolean supportsPolicyScalarAttribute(
                TenantContext tenant, String canonicalKey, ScalarType type) {
            return canonicalKey != null && !canonicalKey.isBlank();
        }

        @Override
        public Context currentContext(
                TenantContext tenant,
                UUID identityId,
                Set<String> canonicalKeys) {
            Context base = contexts.get(identityId);
            if (base == null) return Context.notFound();
            Map<String, CanonicalScalar> values = new LinkedHashMap<>();
            for (String key : canonicalKeys) {
                values.put(key, base.canonicalStrings().getOrDefault(
                        key, CanonicalScalar.unavailable()));
            }
            return new Context(
                    base.status(),
                    base.lifecycleState(),
                    base.identityRevision(),
                    values);
        }

        void setContext(UUID identityId, String lifecycleState, long revision) {
            Context current = contexts.get(identityId);
            contexts.put(
                    identityId,
                    new Context(
                            Status.AVAILABLE,
                            lifecycleState,
                            revision,
                            current == null ? Map.of() : current.canonicalStrings()));
        }

        void setString(UUID identityId, String key, String value, long revision) {
            Context current = contexts.get(identityId);
            if (current == null) {
                current = new Context(Status.AVAILABLE, "ACTIVE", 1, Map.of());
            }
            Map<String, CanonicalScalar> values = new LinkedHashMap<>(
                    current.canonicalStrings());
            values.put(key, CanonicalScalar.trusted(ScalarType.STRING, value, revision));
            contexts.put(
                    identityId,
                    new Context(
                            Status.AVAILABLE,
                            current.lifecycleState(),
                            current.identityRevision(),
                            values));
        }
    }
}
