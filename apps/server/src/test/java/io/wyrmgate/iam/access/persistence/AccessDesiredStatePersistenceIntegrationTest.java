package io.wyrmgate.iam.access.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.wyrmgate.iam.access.application.AccessDesiredStateQueryService;
import io.wyrmgate.iam.access.application.DesiredAccessStateQuery;
import io.wyrmgate.iam.access.application.DesiredStateProjectionRepository.DesiredGrantState;
import io.wyrmgate.iam.access.application.DesiredStateProjectionRepository.DesiredPresence;
import io.wyrmgate.iam.access.application.DesiredStateProjectionRepository.DesiredPrincipalState;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.tenancy.persistence.TenantTestFixture;
import io.wyrmgate.iam.platform.persistence.SharedPostgresIntegrationDatabase;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AccessDesiredStatePersistenceIntegrationTest {

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static TenantTestFixture tenants;
    private static JdbcDesiredStateProjectionRepository repository;
    private static AccessDesiredStateQueryService query;

    @BeforeAll
    static void setUpDatabase() {
        jdbc = SharedPostgresIntegrationDatabase.jdbc();
        ids = new UuidV7Generator();
        tenants = new TenantTestFixture(jdbc, ids);
        repository = new JdbcDesiredStateProjectionRepository(jdbc, ids);
        query = new AccessDesiredStateQueryService(repository);
    }

    @BeforeEach
    void clearRows() {
        jdbc.execute("""
                TRUNCATE TABLE
                    access.desired_grant_state,
                    access.desired_principal_state,
                    tenancy.tenant
                CASCADE
                """);
    }

    @Test
    void principalAndGrantFreshnessAreTenantScopedAndReturnCurrentOrAbsent() {
        TenantContext first = tenant("first");
        TenantContext second = tenant("second");
        Instant now = Instant.parse("2026-09-21T07:00:00Z");

        UUID principalId = ids.nextId();
        repository.replacePrincipal(first, new DesiredPrincipalState(
                principalId,
                ids.nextId(),
                ids.nextId(),
                DesiredPresence.PRESENT,
                7,
                11,
                now));

        UUID grantId = ids.nextId();
        repository.replaceGrant(first, new DesiredGrantState(
                grantId,
                ids.nextId(),
                ids.nextId(),
                ids.nextId(),
                "ANY",
                null,
                DesiredPresence.PRESENT,
                12,
                15,
                now));

        assertThat(query.current(first, DesiredAccessStateQuery.SubjectKind.DESIRED_PRINCIPAL, principalId))
                .isEqualTo(DesiredAccessStateQuery.Freshness.current(7));
        assertThat(query.current(first, DesiredAccessStateQuery.SubjectKind.DESIRED_GRANT, grantId))
                .isEqualTo(DesiredAccessStateQuery.Freshness.current(12));

        assertThat(query.current(second, DesiredAccessStateQuery.SubjectKind.DESIRED_PRINCIPAL, principalId))
                .isEqualTo(DesiredAccessStateQuery.Freshness.absent());
        assertThat(query.current(second, DesiredAccessStateQuery.SubjectKind.DESIRED_GRANT, grantId))
                .isEqualTo(DesiredAccessStateQuery.Freshness.absent());
    }

    @Test
    void projectionReplacementAdvancesDesiredRevisionWithoutBecomingAuthoritativeDomainState() {
        TenantContext tenant = tenant("replace");
        Instant now = Instant.parse("2026-09-21T07:10:00Z");
        UUID stateId = ids.nextId();
        UUID identityId = ids.nextId();
        UUID targetId = ids.nextId();

        repository.replacePrincipal(tenant, new DesiredPrincipalState(
                stateId, identityId, targetId, DesiredPresence.PRESENT, 3, 20, now));
        repository.replacePrincipal(tenant, new DesiredPrincipalState(
                stateId, identityId, targetId, DesiredPresence.ABSENT, 4, 21, now.plusSeconds(1)));

        assertThat(query.current(tenant, DesiredAccessStateQuery.SubjectKind.DESIRED_PRINCIPAL, stateId))
                .isEqualTo(DesiredAccessStateQuery.Freshness.current(4));

        assertThat(jdbc.queryForObject("""
                SELECT desired_state FROM access.desired_principal_state
                WHERE tenant_id = ? AND id = ?
                """, String.class, tenant.tenantId(), stateId))
                .isEqualTo("ABSENT");
    }

    @Test
    void reconcileKeepsStableIdAndRevisionOnReplayButAdvancesGeneration() {
        TenantContext tenant = tenant("reconcile");
        Instant now = Instant.parse("2026-09-21T08:00:00Z");
        UUID identityId = ids.nextId();
        UUID targetId = ids.nextId();
        UUID entitlementId = ids.nextId();

        DesiredGrantState first = repository.reconcileGrant(
                tenant, identityId, targetId, entitlementId, "ANY", null,
                DesiredPresence.PRESENT, now);
        DesiredGrantState replay = repository.reconcileGrant(
                tenant, identityId, targetId, entitlementId, "ANY", null,
                DesiredPresence.PRESENT, now.plusSeconds(1));

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.desiredRevision()).isEqualTo(first.desiredRevision());
        assertThat(replay.sourceGeneration()).isEqualTo(first.sourceGeneration() + 1);

        DesiredGrantState resolved = repository.reconcileGrant(
                tenant, identityId, targetId, entitlementId, "ANY", ids.nextId(),
                DesiredPresence.PRESENT, now.plusSeconds(2));
        assertThat(resolved.desiredRevision()).isEqualTo(first.desiredRevision() + 1);
    }

    @Test
    void principalRefreshCandidatesArePagedAcrossSpecificAndAnyBranches() {
        TenantContext tenant = tenant("principal-refresh");
        Instant now = Instant.parse("2026-09-21T09:00:00Z");
        UUID principalId = ids.nextId();
        UUID targetId = ids.nextId();
        UUID currentIdentityId = ids.nextId();
        UUID previousIdentityId = ids.nextId();

        for (int i = 0; i < 105; i++) {
            repository.reconcileGrant(
                    tenant,
                    ids.nextId(),
                    targetId,
                    ids.nextId(),
                    "SPECIFIC:" + principalId,
                    principalId,
                    DesiredPresence.PRESENT,
                    now.plusSeconds(i));
        }
        for (int i = 0; i < 2; i++) {
            repository.reconcileGrant(
                    tenant,
                    currentIdentityId,
                    targetId,
                    ids.nextId(),
                    "ANY",
                    principalId,
                    DesiredPresence.PRESENT,
                    now.plusSeconds(200 + i));
        }
        repository.reconcileGrant(
                tenant,
                previousIdentityId,
                targetId,
                ids.nextId(),
                "ANY",
                principalId,
                DesiredPresence.PRESENT,
                now.plusSeconds(203));

        var first = repository.findPrincipalRefreshPage(
                tenant,
                principalId,
                targetId,
                currentIdentityId,
                previousIdentityId,
                null,
                100);
        assertThat(first).hasSize(100);
        assertThat(first)
                .extracting(DesiredGrantState::id)
                .isSorted();

        var second = repository.findPrincipalRefreshPage(
                tenant,
                principalId,
                targetId,
                currentIdentityId,
                previousIdentityId,
                first.getLast().id(),
                100);
        assertThat(second).hasSize(8);
        assertThat(second)
                .extracting(DesiredGrantState::id)
                .isSorted();
        assertThat(second.getFirst().id())
                .isGreaterThan(first.getLast().id());
    }

    @Test
    void technicalGrantDemandRemainsPresentUntilFinalCauseIsAbsent() {
        TenantContext tenant = tenant("technical-demand");
        Instant now = Instant.parse("2026-09-21T10:00:00Z");
        UUID targetId = ids.nextId();
        UUID entitlementId = ids.nextId();
        UUID principalId = ids.nextId();

        DesiredGrantState any = repository.reconcileGrant(
                tenant,
                ids.nextId(),
                targetId,
                entitlementId,
                "ANY",
                principalId,
                DesiredPresence.PRESENT,
                now);
        DesiredGrantState specific = repository.reconcileGrant(
                tenant,
                ids.nextId(),
                targetId,
                entitlementId,
                "SPECIFIC:" + principalId,
                principalId,
                DesiredPresence.PRESENT,
                now.plusSeconds(1));

        assertThat(repository.hasPresentTechnicalGrant(
                tenant, targetId, entitlementId, principalId))
                .isTrue();

        repository.reconcileGrant(
                tenant,
                any.identityId(),
                targetId,
                entitlementId,
                any.principalConstraintKey(),
                principalId,
                DesiredPresence.ABSENT,
                now.plusSeconds(2));
        assertThat(repository.hasPresentTechnicalGrant(
                tenant, targetId, entitlementId, principalId))
                .isTrue();

        repository.reconcileGrant(
                tenant,
                specific.identityId(),
                targetId,
                entitlementId,
                specific.principalConstraintKey(),
                principalId,
                DesiredPresence.ABSENT,
                now.plusSeconds(3));
        assertThat(repository.hasPresentTechnicalGrant(
                tenant, targetId, entitlementId, principalId))
                .isFalse();
    }

    private TenantContext tenant(String name) {
        return new TenantContext(tenants.createActive(name, Instant.now()).id());
    }
}
