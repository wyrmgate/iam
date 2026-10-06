package io.wyrmgate.iam.catalog.persistence;

import io.wyrmgate.iam.catalog.application.CatalogQueryModels.PagePosition;
import io.wyrmgate.iam.catalog.application.SsoClientRegistrationRepository;
import io.wyrmgate.iam.catalog.domain.SsoClientLifecycleState;
import io.wyrmgate.iam.catalog.domain.SsoClientRegistration;
import io.wyrmgate.iam.catalog.domain.SsoClientScope;
import io.wyrmgate.iam.platform.persistence.OptimisticUpdate;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcSsoClientRegistrationRepository implements SsoClientRegistrationRepository {

    private final JdbcTemplate jdbc;

    public JdbcSsoClientRegistrationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(TenantContext tenant, SsoClientRegistration registration) {
        jdbc.update("""
                INSERT INTO catalog.sso_client_registration (
                    id, tenant_id, application_id, client_id, requires_governed_access,
                    lifecycle_state, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                registration.id(), tenant.tenantId(), registration.applicationId(), registration.clientId(),
                registration.requiresGovernedAccess(), registration.lifecycleState().name(), registration.revision(),
                Timestamp.from(registration.createdAt()), Timestamp.from(registration.updatedAt()));
        insertRedirectUris(tenant, registration.id(), registration.redirectUris());
        insertScopes(tenant, registration.id(), registration.allowedScopes());
    }

    @Override
    public Optional<SsoClientRegistration> find(TenantContext tenant, UUID registrationId) {
        return jdbc.query("""
                SELECT id, application_id, client_id, requires_governed_access,
                       lifecycle_state, revision, created_at, updated_at
                FROM catalog.sso_client_registration
                WHERE tenant_id = ? AND id = ?
                """, (rs, row) -> map(tenant, rs), tenant.tenantId(), registrationId)
                .stream().findFirst();
    }

    @Override
    public Optional<SsoClientRegistration> findActiveByClientId(TenantContext tenant, String clientId) {
        return jdbc.query("""
                SELECT id, application_id, client_id, requires_governed_access,
                       lifecycle_state, revision, created_at, updated_at
                FROM catalog.sso_client_registration
                WHERE tenant_id = ? AND client_id = ? AND lifecycle_state = 'ACTIVE'
                """, (rs, row) -> map(tenant, rs), tenant.tenantId(), clientId)
                .stream().findFirst();
    }

    @Override
    public List<SsoClientRegistration> findPage(
            TenantContext tenant, UUID applicationId, PagePosition after, int limit) {
        if (after == null) {
            return jdbc.query("""
                    SELECT id, application_id, client_id, requires_governed_access,
                           lifecycle_state, revision, created_at, updated_at
                    FROM catalog.sso_client_registration
                    WHERE tenant_id = ? AND application_id = ?
                    ORDER BY created_at, id
                    LIMIT ?
                    """, (rs, row) -> map(tenant, rs), tenant.tenantId(), applicationId, limit);
        }
        return jdbc.query("""
                SELECT id, application_id, client_id, requires_governed_access,
                       lifecycle_state, revision, created_at, updated_at
                FROM catalog.sso_client_registration
                WHERE tenant_id = ? AND application_id = ?
                  AND (created_at, id) > (?, ?)
                ORDER BY created_at, id
                LIMIT ?
                """, (rs, row) -> map(tenant, rs), tenant.tenantId(), applicationId,
                Timestamp.from(after.createdAt()), after.id(), limit);
    }

    @Override
    public SsoClientRegistration replaceConfiguration(
            TenantContext tenant,
            UUID registrationId,
            Set<String> redirectUris,
            Set<SsoClientScope> allowedScopes,
            boolean requiresGovernedAccess,
            long expectedRevision,
            Instant now) {
        int affected = jdbc.update("""
                UPDATE catalog.sso_client_registration
                SET requires_governed_access = ?, revision = revision + 1, updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ? AND lifecycle_state = 'ACTIVE'
                """, requiresGovernedAccess, Timestamp.from(now), tenant.tenantId(), registrationId, expectedRevision);
        OptimisticUpdate.requireSingleRow(affected, "catalog-sso-client", registrationId, expectedRevision);
        jdbc.update("DELETE FROM catalog.sso_client_redirect_uri WHERE tenant_id = ? AND sso_client_registration_id = ?",
                tenant.tenantId(), registrationId);
        jdbc.update("DELETE FROM catalog.sso_client_scope WHERE tenant_id = ? AND sso_client_registration_id = ?",
                tenant.tenantId(), registrationId);
        insertRedirectUris(tenant, registrationId, redirectUris);
        insertScopes(tenant, registrationId, allowedScopes);
        return find(tenant, registrationId).orElseThrow();
    }

    @Override
    public SsoClientRegistration retire(
            TenantContext tenant,
            UUID registrationId,
            long expectedRevision,
            Instant now) {
        int affected = jdbc.update("""
                UPDATE catalog.sso_client_registration
                SET lifecycle_state = 'RETIRED', revision = revision + 1, updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ? AND lifecycle_state = 'ACTIVE'
                """, Timestamp.from(now), tenant.tenantId(), registrationId, expectedRevision);
        OptimisticUpdate.requireSingleRow(affected, "catalog-sso-client", registrationId, expectedRevision);
        return find(tenant, registrationId).orElseThrow();
    }

    private SsoClientRegistration map(TenantContext tenant, ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        return new SsoClientRegistration(
                id,
                rs.getObject("application_id", UUID.class),
                rs.getString("client_id"),
                loadRedirectUris(tenant, id),
                loadScopes(tenant, id),
                rs.getBoolean("requires_governed_access"),
                SsoClientLifecycleState.valueOf(rs.getString("lifecycle_state")),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private Set<String> loadRedirectUris(TenantContext tenant, UUID registrationId) {
        return new LinkedHashSet<>(jdbc.queryForList("""
                SELECT redirect_uri
                FROM catalog.sso_client_redirect_uri
                WHERE tenant_id = ? AND sso_client_registration_id = ?
                ORDER BY redirect_uri
                """, String.class, tenant.tenantId(), registrationId));
    }

    private Set<SsoClientScope> loadScopes(TenantContext tenant, UUID registrationId) {
        List<String> values = jdbc.queryForList("""
                SELECT scope
                FROM catalog.sso_client_scope
                WHERE tenant_id = ? AND sso_client_registration_id = ?
                ORDER BY scope
                """, String.class, tenant.tenantId(), registrationId);
        LinkedHashSet<SsoClientScope> scopes = new LinkedHashSet<>();
        for (String value : values) scopes.add(SsoClientScope.fromProtocolValue(value));
        return scopes;
    }

    private void insertRedirectUris(TenantContext tenant, UUID registrationId, Set<String> values) {
        for (String value : values) {
            jdbc.update("""
                    INSERT INTO catalog.sso_client_redirect_uri (
                        tenant_id, sso_client_registration_id, redirect_uri)
                    VALUES (?, ?, ?)
                    """, tenant.tenantId(), registrationId, value);
        }
    }

    private void insertScopes(TenantContext tenant, UUID registrationId, Set<SsoClientScope> values) {
        for (SsoClientScope value : values) {
            jdbc.update("""
                    INSERT INTO catalog.sso_client_scope (
                        tenant_id, sso_client_registration_id, scope)
                    VALUES (?, ?, ?)
                    """, tenant.tenantId(), registrationId, value.protocolValue());
        }
    }
}
