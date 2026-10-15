package io.wyrmgate.iam.administration.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.administration.domain.AdministrativeRole;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.JdbcTenantRepository;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class StandaloneTenantOwnerRecoveryPersistenceIntegrationTest {

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static JdbcInitialAdminBootstrapRepository bootstrapRepository;
    private static JdbcStandaloneTenantOwnerRecoveryRepository recoveryRepository;
    private static JdbcAdministrativeAuthorityRepository authorityRepository;

    @BeforeAll
    static void startPostgresAndMigrate() {
        io.wyrmgate.iam.platform.persistence.SharedPostgresIntegrationDatabase.reset();
        var dataSource = io.wyrmgate.iam.platform.persistence.SharedPostgresIntegrationDatabase.dataSource();
        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new JdbcTenantRepository(jdbc, ids);
        bootstrapRepository = new JdbcInitialAdminBootstrapRepository(jdbc);
        recoveryRepository = new JdbcStandaloneTenantOwnerRecoveryRepository(jdbc, ids);
        authorityRepository = new JdbcAdministrativeAuthorityRepository(jdbc);
    }

    @BeforeEach
    void clearRows() {
        jdbc.execute("""
                TRUNCATE TABLE
                    administration.administrative_delegation,
                    administration.administrative_grant,
                    administration.administrative_role_permission,
                    administration.administrative_role,
                    administration.administrative_permission,
                    platform.tenant
                CASCADE
                """);
    }

    @Test
    void upgradesOnlyRoleMembershipAndRevisionToExactFiniteOwnerSet() {
        Instant completedAt = Instant.parse("2026-10-01T00:00:00Z");
        Instant recoveredAt = Instant.parse("2026-10-16T00:00:00Z");
        TenantContext tenant = new TenantContext(tenants.create("Recovery Tenant", completedAt).id());
        UUID roleId = ids.nextId();
        AdministrativeRole role = new AdministrativeRole(
                roleId,
                "tenant-initial-administrator",
                "Tenant Initial Administrator",
                AdministrativePermissions.INITIAL_TENANT_ADMIN,
                1,
                completedAt,
                completedAt);
        bootstrapRepository.insertRole(tenant, role);

        Set<UUID> permissionIds = new LinkedHashSet<>();
        for (AdministrativePermission permission : AdministrativePermissions.INITIAL_TENANT_ADMIN) {
            permissionIds.add(bootstrapRepository.ensurePermission(
                    tenant,
                    ids.nextId(),
                    permission,
                    completedAt));
        }
        bootstrapRepository.attachPermissions(tenant, roleId, permissionIds, completedAt);

        long roleCountBefore = count("administration.administrative_role", tenant);
        long grantCountBefore = count("administration.administrative_grant", tenant);

        recoveryRepository.upgradeLegacyRolePermissions(
                tenant,
                roleId,
                1,
                AdministrativePermissions.INITIAL_TENANT_ADMIN,
                AdministrativePermissions.STANDALONE_TENANT_OWNER,
                recoveredAt);

        AdministrativeRole recovered = authorityRepository.findRole(tenant, roleId).orElseThrow();
        assertThat(recovered.permissions()).isEqualTo(AdministrativePermissions.STANDALONE_TENANT_OWNER);
        assertThat(recovered.revision()).isEqualTo(2);
        assertThat(recovered.createdAt()).isEqualTo(completedAt);
        assertThat(recovered.updatedAt()).isEqualTo(recoveredAt);
        assertThat(count("administration.administrative_role", tenant)).isEqualTo(roleCountBefore);
        assertThat(count("administration.administrative_grant", tenant)).isEqualTo(grantCountBefore);
    }

    private static long count(String table, TenantContext tenant) {
        if (!table.matches("administration\\.[a-z_]+")) {
            throw new IllegalArgumentException("unexpected table");
        }
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE tenant_id = ?",
                Long.class,
                tenant.tenantId());
        return count == null ? 0 : count;
    }
}
