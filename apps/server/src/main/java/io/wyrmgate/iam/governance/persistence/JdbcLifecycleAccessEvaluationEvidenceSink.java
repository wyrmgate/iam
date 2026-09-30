package io.wyrmgate.iam.governance.persistence;

import io.wyrmgate.iam.access.application.LifecycleAccessPrivilegeGuard;
import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.governance.application.LifecycleAccessEvaluationEvidenceSink;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcLifecycleAccessEvaluationEvidenceSink
        implements LifecycleAccessEvaluationEvidenceSink {

    private final JdbcTemplate jdbc;

    public JdbcLifecycleAccessEvaluationEvidenceSink(JdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void record(
            TenantContext tenant,
            UUID evaluationId,
            UUID identityId,
            UUID lifecycleRuleId,
            UUID governancePolicyVersionId,
            AccessAssignment.TargetKind targetKind,
            UUID targetId,
            LifecycleAccessPrivilegeGuard.Decision decision,
            String code,
            List<ConflictEvidence> conflicts,
            Instant evaluatedAt,
            UUID correlationId,
            UUID causationId) {
        conflicts = List.copyOf(conflicts);
        jdbc.update("""
                INSERT INTO governance.lifecycle_access_evaluation (
                    id, tenant_id, identity_id, lifecycle_rule_id,
                    governance_policy_version_id, target_kind, target_id,
                    decision, evaluation_code, conflict_count,
                    evaluated_at, correlation_id, causation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                evaluationId,
                tenant.tenantId(),
                identityId,
                lifecycleRuleId,
                governancePolicyVersionId,
                targetKind.name(),
                targetId,
                decision.name(),
                code,
                conflicts.size(),
                Timestamp.from(evaluatedAt),
                correlationId,
                causationId);
        for (ConflictEvidence conflict : conflicts) {
            jdbc.update("""
                    INSERT INTO governance.lifecycle_access_sod_conflict (
                        tenant_id, evaluation_id, sod_rule_id,
                        severity, enforcement_action, governance_exception_id)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    tenant.tenantId(),
                    evaluationId,
                    conflict.sodRuleId(),
                    conflict.severity().name(),
                    conflict.action().name(),
                    conflict.governanceExceptionId());
        }
    }
}
