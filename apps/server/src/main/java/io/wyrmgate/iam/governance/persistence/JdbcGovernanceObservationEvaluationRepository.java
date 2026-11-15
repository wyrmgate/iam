package io.wyrmgate.iam.governance.persistence;

import io.wyrmgate.iam.governance.application.GovernanceObservationEvaluationRepository;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcGovernanceObservationEvaluationRepository
        implements GovernanceObservationEvaluationRepository {
    private final JdbcTemplate jdbc;

    public JdbcGovernanceObservationEvaluationRepository(JdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
    }

    @Override
    public Checkpoint lockOrStart(TenantContext tenant, UUID binding, long generation, Instant now) {
        jdbc.update("""
                INSERT INTO governance.observation_evaluation
                    (tenant_id, connector_binding_id, source_generation, phase, revision, updated_at)
                VALUES (?, ?, ?, 'ENTITLEMENTS', 1, ?)
                ON CONFLICT (tenant_id, connector_binding_id) DO NOTHING
                """, tenant.tenantId(), binding, generation, Timestamp.from(now));
        Checkpoint current = jdbc.queryForObject("""
                SELECT source_generation, phase, after_key, revision
                FROM governance.observation_evaluation
                WHERE tenant_id = ? AND connector_binding_id = ? FOR UPDATE
                """, (rs,n) -> new Checkpoint(rs.getLong(1), Phase.valueOf(rs.getString(2)),
                        rs.getString(3), rs.getLong(4)), tenant.tenantId(), binding);
        if (current.sourceGeneration() > generation) {
            throw new IllegalStateException("observation generation changed while acquiring checkpoint");
        }
        if (current.sourceGeneration() == generation) return current;
        jdbc.update("""
                UPDATE governance.observation_evaluation
                SET source_generation = ?, phase = 'ENTITLEMENTS', after_key = NULL,
                    revision = revision + 1, updated_at = ?
                WHERE tenant_id = ? AND connector_binding_id = ?
                """, generation, Timestamp.from(now), tenant.tenantId(), binding);
        return new Checkpoint(generation, Phase.ENTITLEMENTS, null, current.revision() + 1);
    }

    @Override
    public java.util.List<DueEvaluation> lockCompletedDue(Instant before,int limit){
        if(limit<1 || limit>100) throw new IllegalArgumentException("refresh limit");
        return jdbc.query("""
                SELECT tenant_id,connector_binding_id,source_generation,phase,after_key,revision
                FROM governance.observation_evaluation
                WHERE phase='COMPLETED' AND updated_at<=?
                ORDER BY updated_at,tenant_id,connector_binding_id LIMIT ? FOR UPDATE SKIP LOCKED
                """,(rs,n)->new DueEvaluation(new TenantContext(rs.getObject(1,UUID.class)),rs.getObject(2,UUID.class),
                        new Checkpoint(rs.getLong(3),Phase.valueOf(rs.getString(4)),rs.getString(5),rs.getLong(6))),
                Timestamp.from(before),limit);
    }

    @Override
    public Checkpoint advance(TenantContext tenant, UUID binding, Checkpoint current,
                              Phase phase, String afterKey, Instant now) {
        int changed = jdbc.update("""
                UPDATE governance.observation_evaluation
                SET phase = ?, after_key = ?, revision = revision + 1, updated_at = ?
                WHERE tenant_id = ? AND connector_binding_id = ?
                  AND source_generation = ? AND revision = ?
                """, phase.name(), afterKey, Timestamp.from(now), tenant.tenantId(), binding,
                current.sourceGeneration(), current.revision());
        if (changed != 1) throw new IllegalStateException("observation checkpoint changed");
        return new Checkpoint(current.sourceGeneration(), phase, afterKey, current.revision() + 1);
    }
}
