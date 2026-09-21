package io.wyrmgate.iam.catalog.persistence;

import io.wyrmgate.iam.catalog.application.CatalogQueryModels.PagePosition;
import io.wyrmgate.iam.catalog.application.CatalogRepository;
import io.wyrmgate.iam.catalog.domain.Application;
import io.wyrmgate.iam.catalog.domain.ApplicationTarget;
import io.wyrmgate.iam.catalog.domain.CatalogLifecycleState;
import io.wyrmgate.iam.catalog.domain.Entitlement;
import io.wyrmgate.iam.platform.persistence.OptimisticUpdate;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcCatalogRepository implements CatalogRepository {

    private final JdbcTemplate jdbc;

    public JdbcCatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void insertApplication(TenantContext tenant, Application application) {
        jdbc.update("""
                INSERT INTO catalog.application (
                    id, tenant_id, code, name, lifecycle_state,
                    revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                application.id(), tenant.tenantId(), application.code(), application.name(),
                application.lifecycleState().name(), application.revision(),
                Timestamp.from(application.createdAt()), Timestamp.from(application.updatedAt()));
    }

    @Override
    public Optional<Application> findApplication(TenantContext tenant, UUID applicationId) {
        return jdbc.query("""
                SELECT id, code, name, lifecycle_state, revision, created_at, updated_at
                FROM catalog.application
                WHERE tenant_id = ? AND id = ?
                """,
                (rs,row) -> new Application(
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        rs.getString("name"),
                        CatalogLifecycleState.valueOf(rs.getString("lifecycle_state")),
                        rs.getLong("revision"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                tenant.tenantId(), applicationId).stream().findFirst();
    }

    @Override
    public List<Application> findApplicationPage(TenantContext tenant, PagePosition after, int limit) {
        if (after == null) {
            return jdbc.query("""
                    SELECT id, code, name, lifecycle_state, revision, created_at, updated_at
                    FROM catalog.application
                    WHERE tenant_id = ?
                    ORDER BY created_at, id
                    LIMIT ?
                    """, (rs,row) -> application(rs), tenant.tenantId(), limit);
        }
        return jdbc.query("""
                SELECT id, code, name, lifecycle_state, revision, created_at, updated_at
                FROM catalog.application
                WHERE tenant_id = ?
                  AND (created_at, id) > (?, ?)
                ORDER BY created_at, id
                LIMIT ?
                """, (rs,row) -> application(rs),
                tenant.tenantId(), Timestamp.from(after.createdAt()), after.id(), limit);
    }

    @Override
    public Application updateApplicationName(
            TenantContext tenant,
            UUID applicationId,
            String name,
            long expectedRevision,
            Instant now) {
        int affected = jdbc.update("""
                UPDATE catalog.application
                SET name = ?, revision = revision + 1, updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ? AND lifecycle_state = 'ACTIVE'
                """, name, Timestamp.from(now), tenant.tenantId(), applicationId, expectedRevision);
        OptimisticUpdate.requireSingleRow(
                affected, "catalog-application", applicationId, expectedRevision);
        return findApplication(tenant, applicationId).orElseThrow();
    }

    @Override
    public Application retireApplication(
            TenantContext tenant,
            UUID applicationId,
            long expectedRevision,
            Instant now) {
        int affected = jdbc.update("""
                UPDATE catalog.application
                SET lifecycle_state = 'RETIRED', revision = revision + 1, updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ? AND lifecycle_state = 'ACTIVE'
                """, Timestamp.from(now), tenant.tenantId(), applicationId, expectedRevision);
        OptimisticUpdate.requireSingleRow(
                affected, "catalog-application", applicationId, expectedRevision);
        return findApplication(tenant, applicationId).orElseThrow();
    }

    @Override
    public void insertTarget(TenantContext tenant, ApplicationTarget target) {
        jdbc.update("""
                INSERT INTO catalog.application_target (
                    id, tenant_id, application_id, code, lifecycle_state,
                    revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                target.id(), tenant.tenantId(), target.applicationId(), target.code(),
                target.lifecycleState().name(), target.revision(),
                Timestamp.from(target.createdAt()), Timestamp.from(target.updatedAt()));
    }

    @Override
    public Optional<ApplicationTarget> findTarget(TenantContext tenant, UUID targetId) {
        return jdbc.query("""
                SELECT id, application_id, code, lifecycle_state, revision, created_at, updated_at
                FROM catalog.application_target
                WHERE tenant_id = ? AND id = ?
                """, (rs,row) -> target(rs), tenant.tenantId(), targetId).stream().findFirst();
    }

    @Override
    public List<ApplicationTarget> findTargetPage(
            TenantContext tenant, UUID applicationId, PagePosition after, int limit) {
        if (after == null) {
            return jdbc.query("""
                    SELECT id, application_id, code, lifecycle_state, revision, created_at, updated_at
                    FROM catalog.application_target
                    WHERE tenant_id = ? AND application_id = ?
                    ORDER BY created_at, id
                    LIMIT ?
                    """, (rs,row) -> target(rs), tenant.tenantId(), applicationId, limit);
        }
        return jdbc.query("""
                SELECT id, application_id, code, lifecycle_state, revision, created_at, updated_at
                FROM catalog.application_target
                WHERE tenant_id = ? AND application_id = ?
                  AND (created_at, id) > (?, ?)
                ORDER BY created_at, id
                LIMIT ?
                """, (rs,row) -> target(rs), tenant.tenantId(), applicationId,
                Timestamp.from(after.createdAt()), after.id(), limit);
    }

    @Override
    public ApplicationTarget retireTarget(
            TenantContext tenant, UUID targetId, long expectedRevision, Instant now) {
        int affected = jdbc.update("""
                UPDATE catalog.application_target
                SET lifecycle_state = 'RETIRED', revision = revision + 1, updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ? AND lifecycle_state = 'ACTIVE'
                """, Timestamp.from(now), tenant.tenantId(), targetId, expectedRevision);
        OptimisticUpdate.requireSingleRow(
                affected, "catalog-application-target", targetId, expectedRevision);
        return findTarget(tenant, targetId).orElseThrow();
    }

    @Override
    public void insertEntitlement(TenantContext tenant, Entitlement entitlement) {
        jdbc.update("""
                INSERT INTO catalog.entitlement (
                    id, tenant_id, application_id, application_target_id,
                    code, native_key, entitlement_type, lifecycle_state,
                    revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                entitlement.id(), tenant.tenantId(), entitlement.applicationId(),
                entitlement.applicationTargetId(), entitlement.code(), entitlement.nativeKey(),
                entitlement.entitlementType(), entitlement.lifecycleState().name(),
                entitlement.revision(), Timestamp.from(entitlement.createdAt()),
                Timestamp.from(entitlement.updatedAt()));
    }

    @Override
    public Optional<Entitlement> findEntitlement(TenantContext tenant, UUID entitlementId) {
        return jdbc.query("""
                SELECT id, application_id, application_target_id, code, native_key,
                       entitlement_type, lifecycle_state, revision, created_at, updated_at
                FROM catalog.entitlement
                WHERE tenant_id = ? AND id = ?
                """, (rs,row) -> entitlement(rs), tenant.tenantId(), entitlementId)
                .stream().findFirst();
    }

    @Override
    public List<Entitlement> findEntitlementPage(
            TenantContext tenant, UUID applicationId, PagePosition after, int limit) {
        if (after == null) {
            return jdbc.query("""
                    SELECT id, application_id, application_target_id, code, native_key,
                           entitlement_type, lifecycle_state, revision, created_at, updated_at
                    FROM catalog.entitlement
                    WHERE tenant_id = ? AND application_id = ?
                    ORDER BY created_at, id
                    LIMIT ?
                    """, (rs,row) -> entitlement(rs), tenant.tenantId(), applicationId, limit);
        }
        return jdbc.query("""
                SELECT id, application_id, application_target_id, code, native_key,
                       entitlement_type, lifecycle_state, revision, created_at, updated_at
                FROM catalog.entitlement
                WHERE tenant_id = ? AND application_id = ?
                  AND (created_at, id) > (?, ?)
                ORDER BY created_at, id
                LIMIT ?
                """, (rs,row) -> entitlement(rs), tenant.tenantId(), applicationId,
                Timestamp.from(after.createdAt()), after.id(), limit);
    }

    @Override
    public Entitlement retireEntitlement(
            TenantContext tenant, UUID entitlementId, long expectedRevision, Instant now) {
        int affected = jdbc.update("""
                UPDATE catalog.entitlement
                SET lifecycle_state = 'RETIRED', revision = revision + 1, updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ? AND lifecycle_state = 'ACTIVE'
                """, Timestamp.from(now), tenant.tenantId(), entitlementId, expectedRevision);
        OptimisticUpdate.requireSingleRow(
                affected, "catalog-entitlement", entitlementId, expectedRevision);
        return findEntitlement(tenant, entitlementId).orElseThrow();
    }

    private static Application application(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Application(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                CatalogLifecycleState.valueOf(rs.getString("lifecycle_state")),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static ApplicationTarget target(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ApplicationTarget(
                rs.getObject("id", UUID.class),
                rs.getObject("application_id", UUID.class),
                rs.getString("code"),
                CatalogLifecycleState.valueOf(rs.getString("lifecycle_state")),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static Entitlement entitlement(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Entitlement(
                rs.getObject("id", UUID.class),
                rs.getObject("application_id", UUID.class),
                rs.getObject("application_target_id", UUID.class),
                rs.getString("code"),
                rs.getString("native_key"),
                rs.getString("entitlement_type"),
                CatalogLifecycleState.valueOf(rs.getString("lifecycle_state")),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
