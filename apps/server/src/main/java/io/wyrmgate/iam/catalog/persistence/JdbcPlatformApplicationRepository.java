package io.wyrmgate.iam.catalog.persistence;

import io.wyrmgate.iam.catalog.application.PlatformApplicationRepository;
import io.wyrmgate.iam.catalog.domain.ApplicationTenancyMode;
import io.wyrmgate.iam.catalog.domain.CatalogLifecycleState;
import io.wyrmgate.iam.catalog.domain.PlatformApplicationAdmissionMode;
import io.wyrmgate.iam.catalog.domain.PlatformApplicationBinding;
import io.wyrmgate.iam.catalog.domain.PlatformApplicationOffering;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC persistence for ADR-0049 platform application offerings, admissions and bindings. */
public final class JdbcPlatformApplicationRepository implements PlatformApplicationRepository {

    private final JdbcTemplate jdbc;

    public JdbcPlatformApplicationRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void insertOffering(PlatformApplicationOffering offering) {
        jdbc.update(
                """
                INSERT INTO catalog.platform_application_offering (
                    id, code, name, tenancy_mode, admission_mode, governed_policy_ref,
                    lifecycle_state, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                offering.id(),
                offering.code(),
                offering.name(),
                offering.tenancyMode().name(),
                offering.admissionMode().name(),
                offering.governedPolicyRef(),
                offering.lifecycleState().name(),
                offering.revision(),
                Timestamp.from(offering.createdAt()),
                Timestamp.from(offering.updatedAt()));
    }

    @Override
    public Optional<PlatformApplicationOffering> findOffering(UUID offeringId) {
        List<PlatformApplicationOffering> rows = jdbc.query(
                """
                SELECT id, code, name, tenancy_mode, admission_mode, governed_policy_ref,
                       lifecycle_state, revision, created_at, updated_at
                FROM catalog.platform_application_offering
                WHERE id = ?
                """,
                (rs, rowNum) -> mapOffering(rs),
                offeringId);
        return rows.stream().findFirst();
    }

    @Override
    public void admitSelectedTenant(UUID offeringId, TenantContext tenant, Instant now) {
        jdbc.update(
                """
                INSERT INTO catalog.platform_application_tenant_admission (
                    platform_application_id, tenant_id, created_at)
                VALUES (?, ?, ?)
                ON CONFLICT (platform_application_id, tenant_id) DO NOTHING
                """,
                offeringId,
                tenant.tenantId(),
                Timestamp.from(now));
    }

    @Override
    public boolean isSelectedTenantAdmitted(UUID offeringId, TenantContext tenant) {
        Integer count = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM catalog.platform_application_tenant_admission
                WHERE platform_application_id = ? AND tenant_id = ?
                """,
                Integer.class,
                offeringId,
                tenant.tenantId());
        return count != null && count == 1;
    }

    @Override
    public void insertBinding(PlatformApplicationBinding binding) {
        jdbc.update(
                """
                INSERT INTO catalog.platform_application_binding (
                    id, platform_application_id, tenant_id, application_id,
                    lifecycle_state, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                binding.id(),
                binding.platformApplicationId(),
                binding.tenantId(),
                binding.applicationId(),
                binding.lifecycleState().name(),
                binding.revision(),
                Timestamp.from(binding.createdAt()),
                Timestamp.from(binding.updatedAt()));
    }

    @Override
    public Optional<PlatformApplicationBinding> findBinding(UUID offeringId, TenantContext tenant) {
        List<PlatformApplicationBinding> rows = jdbc.query(
                """
                SELECT id, platform_application_id, tenant_id, application_id,
                       lifecycle_state, revision, created_at, updated_at
                FROM catalog.platform_application_binding
                WHERE platform_application_id = ? AND tenant_id = ?
                """,
                (rs, rowNum) -> mapBinding(rs),
                offeringId,
                tenant.tenantId());
        return rows.stream().findFirst();
    }

    private static PlatformApplicationOffering mapOffering(ResultSet rs) throws SQLException {
        return new PlatformApplicationOffering(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                ApplicationTenancyMode.valueOf(rs.getString("tenancy_mode")),
                PlatformApplicationAdmissionMode.valueOf(rs.getString("admission_mode")),
                rs.getString("governed_policy_ref"),
                CatalogLifecycleState.valueOf(rs.getString("lifecycle_state")),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static PlatformApplicationBinding mapBinding(ResultSet rs) throws SQLException {
        return new PlatformApplicationBinding(
                rs.getObject("id", UUID.class),
                rs.getObject("platform_application_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("application_id", UUID.class),
                CatalogLifecycleState.valueOf(rs.getString("lifecycle_state")),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
