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
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class AccessDesiredStatePersistenceIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");
    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static JdbcDesiredStateProjectionRepository repository;
    private static AccessDesiredStateQueryService query;

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
        repository = new JdbcDesiredStateProjectionRepository(jdbc);
        query = new AccessDesiredStateQueryService(repository);
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("18");
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
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
    void queryServiceReturnsUnavailableWhenProjectionStoreCannotBeRead() {
        TenantContext tenant = new TenantContext(ids.nextId());
        var unavailable = new AccessDesiredStateQueryService(new io.wyrmgate.iam.access.application.DesiredStateProjectionRepository() {
            @Override
            public void replacePrincipal(TenantContext tenant, DesiredPrincipalState state) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void replaceGrant(TenantContext tenant, DesiredGrantState state) {
                throw new UnsupportedOperationException();
            }

            @Override
            public DesiredAccessStateQuery.Freshness principalFreshness(TenantContext tenant, UUID id) {
                throw new IllegalStateException("projection store unavailable");
            }

            @Override
            public DesiredAccessStateQuery.Freshness grantFreshness(TenantContext tenant, UUID id) {
                throw new IllegalStateException("projection store unavailable");
            }
        });

        assertThat(unavailable.current(
                tenant, DesiredAccessStateQuery.SubjectKind.DESIRED_PRINCIPAL, ids.nextId()))
                .isEqualTo(DesiredAccessStateQuery.Freshness.unavailable());
    }

    private TenantContext tenant(String name) {
        return new TenantContext(tenants.create(name, Instant.now()).id());
    }
}
