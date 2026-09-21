package io.wyrmgate.iam.access.persistence;

import io.wyrmgate.iam.access.application.DesiredAccessStateQuery;
import io.wyrmgate.iam.access.application.DesiredStateProjectionRepository;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcDesiredStateProjectionRepository implements DesiredStateProjectionRepository {

    private final JdbcTemplate jdbc;

    public JdbcDesiredStateProjectionRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
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
                    id, tenant_id, identity_id, application_target_id, entitlement_id, principal_id,
                    desired_state, desired_revision, source_generation, computed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, id) DO UPDATE
                SET identity_id = EXCLUDED.identity_id,
                    application_target_id = EXCLUDED.application_target_id,
                    entitlement_id = EXCLUDED.entitlement_id,
                    principal_id = EXCLUDED.principal_id,
                    desired_state = EXCLUDED.desired_state,
                    desired_revision = EXCLUDED.desired_revision,
                    source_generation = EXCLUDED.source_generation,
                    computed_at = EXCLUDED.computed_at
                """,
                state.id(), tenant.tenantId(), state.identityId(), state.applicationTargetId(),
                state.entitlementId(), state.principalId(), state.desiredState().name(),
                state.desiredRevision(), state.sourceGeneration(), Timestamp.from(state.computedAt()));
    }

    @Override
    public DesiredAccessStateQuery.Freshness principalFreshness(TenantContext tenant, UUID id) {
        return freshness("access.desired_principal_state", tenant, id);
    }

    @Override
    public DesiredAccessStateQuery.Freshness grantFreshness(TenantContext tenant, UUID id) {
        return freshness("access.desired_grant_state", tenant, id);
    }

    private DesiredAccessStateQuery.Freshness freshness(String table, TenantContext tenant, UUID id) {
        if (!table.equals("access.desired_principal_state") && !table.equals("access.desired_grant_state")) {
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
}
