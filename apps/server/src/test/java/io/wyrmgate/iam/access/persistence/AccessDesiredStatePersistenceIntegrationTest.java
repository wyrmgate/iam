package io.wyrmgate.iam.access.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.wyrmgate.iam.access.application.AccessDesiredStateQueryService;
import io.wyrmgate.iam.access.application.DesiredAccessStateQuery;
import io.wyrmgate.iam.access.application.DesiredStateProjectionRepository.DesiredGrantState;
import io.wyrmgate.iam.access.application.DesiredStateProjectionRepository.DesiredPresence;
import io.wyrmgate.iam.access.application.DesiredStateProjectionRepository.DesiredPrincipalState;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.JdbcTenantRepository;
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
    private static JdbcTenantRepository tenants;
    private static JdbcDesiredStateProjectionRepository repository;
    private static AccessDesiredStateQueryService query;

    @BeforeAll
    static void setUpDatabase() {
        jdbc = SharedPostgresIntegrationDatabase.jdbc();
        ids = new UuidV7Generator();
        tenants = new JdbcTenantRepository(jdbc, ids);
        repository = new JdbcDesiredStateProjectionRepository(jdbc, ids);
        query = new AccessDesiredStateQueryService(repository);
    }

    @BeforeEach
    void clearRows() {
        jdbc.execute("""
                TRUNCATE TABLE
                    access.desired_grant_state,
                    access.desired_principal_state,
                    platform.tenant
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

    private TenantContext tenant(String name) {
        return new TenantContext(tenants.create(name, Instant.now()).id());
    }
}
