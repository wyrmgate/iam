package io.wyrmgate.iam.access.persistence;

import io.wyrmgate.iam.access.application.DesiredAccessStateQuery;
import io.wyrmgate.iam.access.application.DesiredStateProjectionRepository;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcDesiredStateProjectionRepository
        implements DesiredStateProjectionRepository {

    private final JdbcTemplate jdbc;
    private final IdGenerator ids;

    public JdbcDesiredStateProjectionRepository(JdbcTemplate jdbc, IdGenerator ids) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    public void replacePrincipal(TenantContext tenant, DesiredPrincipalState state) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(state, "state");
        jdbc.update("""
                INSERT INTO access.desired_principal_state (
                    id, tenant_id, identity_id, application_target_id, desired_state,
                    desired_revision, source_generation, computed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, id) DO UPDATE
                SET identity_id = EXCLUDED.identity_id,
                    application_target_id = EXCLUDED.application_target_id,
                    desired_state = EXCLUDED.desired_state,
                    desired_revision = EXCLUDED.desired_revision,
                    source_generation = EXCLUDED.source_generation,
                    computed_at = EXCLUDED.computed_at
                """,
                state.id(), tenant.tenantId(), state.identityId(), state.applicationTargetId(),
                state.desiredState().name(), state.desiredRevision(), state.sourceGeneration(),
                Timestamp.from(state.computedAt()));
    }

    @Override
    public void replaceGrant(TenantContext tenant, DesiredGrantState state) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(state, "state");
        jdbc.update("""
                INSERT INTO access.desired_grant_state (
                    id, tenant_id, identity_id, application_target_id, entitlement_id,
                    principal_constraint_key, principal_id,
                    desired_state, desired_revision, source_generation, computed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, id) DO UPDATE
                SET identity_id = EXCLUDED.identity_id,
                    application_target_id = EXCLUDED.application_target_id,
                    entitlement_id = EXCLUDED.entitlement_id,
                    principal_constraint_key = EXCLUDED.principal_constraint_key,
                    principal_id = EXCLUDED.principal_id,
                    desired_state = EXCLUDED.desired_state,
                    desired_revision = EXCLUDED.desired_revision,
                    source_generation = EXCLUDED.source_generation,
                    computed_at = EXCLUDED.computed_at
                """,
                state.id(), tenant.tenantId(), state.identityId(), state.applicationTargetId(),
                state.entitlementId(), state.principalConstraintKey(), state.principalId(),
                state.desiredState().name(), state.desiredRevision(), state.sourceGeneration(),
                Timestamp.from(state.computedAt()));
    }

    @Override
    public DesiredGrantState reconcileGrant(
            TenantContext tenant,
            UUID identityId,
            UUID applicationTargetId,
            UUID entitlementId,
            String principalConstraintKey,
            UUID principalId,
            DesiredPresence desiredState,
            Instant computedAt) {
        List<DesiredGrantState> existing = jdbc.query("""
                SELECT id, identity_id, application_target_id, entitlement_id,
                       principal_constraint_key, principal_id, desired_state,
                       desired_revision, source_generation, computed_at
                FROM access.desired_grant_state
                WHERE tenant_id = ?
                  AND identity_id = ?
                  AND application_target_id = ?
                  AND entitlement_id = ?
                  AND principal_constraint_key = ?
                """,
                (rs,row) -> grant(rs),
                tenant.tenantId(), identityId, applicationTargetId,
                entitlementId, principalConstraintKey);

        if (existing.isEmpty()) {
            DesiredGrantState created = new DesiredGrantState(
                    ids.nextId(), identityId, applicationTargetId, entitlementId,
                    principalConstraintKey, principalId, desiredState, 1, 1, computedAt);
            replaceGrant(tenant, created);
            return created;
        }

        DesiredGrantState current = existing.getFirst();
        boolean meaningfulChange = current.desiredState() != desiredState
                || !Objects.equals(current.principalId(), principalId);
        DesiredGrantState updated = new DesiredGrantState(
                current.id(),
                current.identityId(),
                current.applicationTargetId(),
                current.entitlementId(),
                current.principalConstraintKey(),
                principalId,
                desiredState,
                meaningfulChange ? current.desiredRevision() + 1 : current.desiredRevision(),
                current.sourceGeneration() + 1,
                computedAt);
        replaceGrant(tenant, updated);
        return updated;
    }

    @Override
    public DesiredPrincipalState reconcilePrincipal(
            TenantContext tenant,
            UUID identityId,
            UUID applicationTargetId,
            DesiredPresence desiredState,
            Instant computedAt) {
        List<DesiredPrincipalState> existing = jdbc.query("""
                SELECT id, identity_id, application_target_id, desired_state,
                       desired_revision, source_generation, computed_at
                FROM access.desired_principal_state
                WHERE tenant_id = ?
                  AND identity_id = ?
                  AND application_target_id = ?
                """,
                (rs,row) -> principal(rs),
                tenant.tenantId(), identityId, applicationTargetId);

        if (existing.isEmpty()) {
            DesiredPrincipalState created = new DesiredPrincipalState(
                    ids.nextId(), identityId, applicationTargetId,
                    desiredState, 1, 1, computedAt);
            replacePrincipal(tenant, created);
            return created;
        }

        DesiredPrincipalState current = existing.getFirst();
        boolean meaningfulChange = current.desiredState() != desiredState;
        DesiredPrincipalState updated = new DesiredPrincipalState(
                current.id(),
                current.identityId(),
                current.applicationTargetId(),
                desiredState,
                meaningfulChange ? current.desiredRevision() + 1 : current.desiredRevision(),
                current.sourceGeneration() + 1,
                computedAt);
        replacePrincipal(tenant, updated);
        return updated;
    }

    @Override
    public boolean hasPresentGrant(
            TenantContext tenant,
            UUID identityId,
            UUID applicationTargetId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*)
                FROM access.desired_grant_state
                WHERE tenant_id = ?
                  AND identity_id = ?
                  AND application_target_id = ?
                  AND desired_state = 'PRESENT'
                """,
                Integer.class,
                tenant.tenantId(), identityId, applicationTargetId);
        return count != null && count > 0;
    }

    @Override
    public List<DesiredGrantState> findPresentAnyGrants(
            TenantContext tenant,
            UUID identityId,
            UUID applicationTargetId) {
        return jdbc.query("""
                SELECT id, identity_id, application_target_id, entitlement_id,
                       principal_constraint_key, principal_id, desired_state,
                       desired_revision, source_generation, computed_at
                FROM access.desired_grant_state
                WHERE tenant_id = ?
                  AND identity_id = ?
                  AND application_target_id = ?
                  AND principal_constraint_key = 'ANY'
                  AND desired_state = 'PRESENT'
                ORDER BY id
                """,
                (rs,row) -> grant(rs),
                tenant.tenantId(), identityId, applicationTargetId);
    }

    @Override
    public java.util.Optional<DesiredGrantState> findGrantTuple(
            TenantContext tenant,
            UUID identityId,
            UUID entitlementId,
            String principalConstraintKey) {
        return jdbc.query("""
                SELECT id, identity_id, application_target_id, entitlement_id,
                       principal_constraint_key, principal_id, desired_state,
                       desired_revision, source_generation, computed_at
                FROM access.desired_grant_state
                WHERE tenant_id = ?
                  AND identity_id = ?
                  AND entitlement_id = ?
                  AND principal_constraint_key = ?
                """,
                (rs,row) -> grant(rs),
                tenant.tenantId(), identityId, entitlementId, principalConstraintKey)
                .stream()
                .findFirst();
    }

    @Override
    public java.util.Optional<DesiredGrantState> findGrantById(
            TenantContext tenant, UUID desiredGrantId) {
        return jdbc.query("""
                SELECT id, identity_id, application_target_id, entitlement_id,
                       principal_constraint_key, principal_id, desired_state,
                       desired_revision, source_generation, computed_at
                FROM access.desired_grant_state
                WHERE tenant_id = ? AND id = ?
                """,
                (rs,row) -> grant(rs),
                tenant.tenantId(), desiredGrantId)
                .stream()
                .findFirst();
    }

    @Override
    public DesiredAccessStateQuery.Freshness principalFreshness(
            TenantContext tenant, UUID id) {
        return freshness("access.desired_principal_state", tenant, id);
    }

    @Override
    public DesiredAccessStateQuery.Freshness grantFreshness(
            TenantContext tenant, UUID id) {
        return freshness("access.desired_grant_state", tenant, id);
    }

    private DesiredAccessStateQuery.Freshness freshness(
            String table, TenantContext tenant, UUID id) {
        if (!table.equals("access.desired_principal_state")
                && !table.equals("access.desired_grant_state")) {
            throw new IllegalArgumentException("unsupported desired-state table");
        }
        List<Long> rows = jdbc.query(
                "SELECT desired_revision FROM " + table + " WHERE tenant_id = ? AND id = ?",
                (rs, row) -> rs.getLong(1),
                tenant.tenantId(), id);
        return rows.isEmpty()
                ? DesiredAccessStateQuery.Freshness.absent()
                : DesiredAccessStateQuery.Freshness.current(rows.getFirst());
    }

    private static DesiredGrantState grant(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new DesiredGrantState(
                rs.getObject("id", UUID.class),
                rs.getObject("identity_id", UUID.class),
                rs.getObject("application_target_id", UUID.class),
                rs.getObject("entitlement_id", UUID.class),
                rs.getString("principal_constraint_key"),
                rs.getObject("principal_id", UUID.class),
                DesiredPresence.valueOf(rs.getString("desired_state")),
                rs.getLong("desired_revision"),
                rs.getLong("source_generation"),
                rs.getTimestamp("computed_at").toInstant());
    }

    private static DesiredPrincipalState principal(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new DesiredPrincipalState(
                rs.getObject("id", UUID.class),
                rs.getObject("identity_id", UUID.class),
                rs.getObject("application_target_id", UUID.class),
                DesiredPresence.valueOf(rs.getString("desired_state")),
                rs.getLong("desired_revision"),
                rs.getLong("source_generation"),
                rs.getTimestamp("computed_at").toInstant());
    }
}
