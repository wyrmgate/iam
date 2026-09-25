package io.wyrmgate.iam.identity.persistence;

import io.wyrmgate.iam.identity.application.PrincipalCommandException;
import io.wyrmgate.iam.identity.application.PrincipalRepository;
import io.wyrmgate.iam.identity.domain.Principal;
import io.wyrmgate.iam.identity.domain.PrincipalKind;
import io.wyrmgate.iam.identity.domain.PrincipalLifecycleState;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcPrincipalRepository implements PrincipalRepository {

    private final JdbcTemplate jdbc;

    public JdbcPrincipalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(TenantContext tenant, Principal principal) {
        try {
            jdbc.update("""
                    INSERT INTO identity.principal (
                        id, tenant_id, identity_id, application_target_id,
                        principal_kind, native_principal_key, lifecycle_state,
                        revision, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    principal.id(),
                    tenant.tenantId(),
                    principal.identityId(),
                    principal.applicationTargetId(),
                    principal.kind().name(),
                    principal.nativePrincipalKey(),
                    principal.lifecycleState().name(),
                    principal.revision(),
                    Timestamp.from(principal.createdAt()),
                    Timestamp.from(principal.updatedAt()));
        } catch (DataIntegrityViolationException conflict) {
            throw new PrincipalCommandException(
                    "principal_conflict",
                    "A Principal already exists for this target and native principal key.");
        }
    }

    @Override
    public Optional<Principal> findById(
            TenantContext tenant, UUID principalId) {
        return jdbc.query("""
                SELECT id, identity_id, application_target_id, principal_kind,
                       native_principal_key, lifecycle_state, revision,
                       created_at, updated_at
                FROM identity.principal
                WHERE tenant_id = ? AND id = ?
                """, (rs,row) -> principal(rs), tenant.tenantId(), principalId)
                .stream().findFirst();
    }

    @Override
    public Optional<Principal> findByTargetAndNativeKey(
            TenantContext tenant,
            UUID applicationTargetId,
            String nativePrincipalKey) {
        return jdbc.query("""
                SELECT id, identity_id, application_target_id, principal_kind,
                       native_principal_key, lifecycle_state, revision,
                       created_at, updated_at
                FROM identity.principal
                WHERE tenant_id = ? AND application_target_id = ?
                  AND native_principal_key = ?
                """, (rs,row) -> principal(rs),
                tenant.tenantId(), applicationTargetId, nativePrincipalKey)
                .stream().findFirst();
    }

    @Override
    public List<Principal> findActiveByIdentityAndTarget(
            TenantContext tenant,
            UUID identityId,
            UUID applicationTargetId) {
        return jdbc.query("""
                SELECT id, identity_id, application_target_id, principal_kind,
                       native_principal_key, lifecycle_state, revision,
                       created_at, updated_at
                FROM identity.principal
                WHERE tenant_id = ?
                  AND identity_id = ?
                  AND application_target_id = ?
                  AND lifecycle_state = 'ACTIVE'
                ORDER BY id
                """,
                (rs,row) -> principal(rs),
                tenant.tenantId(), identityId, applicationTargetId);
    }

    @Override
    public Principal correlate(
            TenantContext tenant,
            UUID principalId,
            UUID identityId,
            long expectedRevision,
            Instant now) {
        int affected = jdbc.update("""
                UPDATE identity.principal
                SET identity_id = ?, revision = revision + 1, updated_at = ?
                WHERE tenant_id = ? AND id = ?
                  AND revision = ? AND identity_id IS NULL
                """,
                identityId, Timestamp.from(now),
                tenant.tenantId(), principalId, expectedRevision);
        if (affected != 1) {
            Principal existing = findById(tenant, principalId)
                    .orElseThrow(() -> new PrincipalCommandException(
                            "principal_not_found",
                            "The requested Principal was not found."));
            if (existing.revision() != expectedRevision) {
                throw new StaleWriteException(
                        "principal", principalId, expectedRevision);
            }
            if (existing.identityId() != null) {
                throw new PrincipalCommandException(
                        "principal_already_correlated",
                        "The Principal is already correlated and cannot be reassigned.");
            }
            throw new IllegalStateException("principal correlation did not update");
        }
        return findById(tenant, principalId).orElseThrow();
    }

    private static Principal principal(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new Principal(
                rs.getObject("id", UUID.class),
                rs.getObject("identity_id", UUID.class),
                rs.getObject("application_target_id", UUID.class),
                PrincipalKind.valueOf(rs.getString("principal_kind")),
                rs.getString("native_principal_key"),
                PrincipalLifecycleState.valueOf(rs.getString("lifecycle_state")),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
