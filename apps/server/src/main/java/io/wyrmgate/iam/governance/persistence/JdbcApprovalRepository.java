package io.wyrmgate.iam.governance.persistence;

import io.wyrmgate.iam.governance.application.ApprovalRepository;
import io.wyrmgate.iam.governance.domain.ApprovalDecision;
import io.wyrmgate.iam.governance.domain.ApprovalParticipant;
import io.wyrmgate.iam.governance.domain.ApprovalPlan;
import io.wyrmgate.iam.governance.domain.ApprovalStage;
import io.wyrmgate.iam.governance.domain.ApprovalSubject;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcApprovalRepository
        implements ApprovalRepository {

    private final JdbcTemplate jdbc;

    public JdbcApprovalRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void insertPlan(
            TenantContext tenant, ApprovalPlan plan) {
        jdbc.update("""
                INSERT INTO governance.approval_plan (
                    id, tenant_id, subject_kind, subject_id,
                    lifecycle_state, current_stage_ordinal,
                    deadline_at, revision, created_at, updated_at,
                    completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                plan.id(),
                tenant.tenantId(),
                plan.subject().kind().name(),
                plan.subject().id(),
                plan.lifecycleState().name(),
                plan.currentStageOrdinal(),
                nullableTimestamp(plan.deadlineAt()),
                plan.revision(),
                Timestamp.from(plan.createdAt()),
                Timestamp.from(plan.updatedAt()),
                nullableTimestamp(plan.completedAt()));
    }

    @Override
    public void insertStage(
            TenantContext tenant, ApprovalStage stage) {
        jdbc.update("""
                INSERT INTO governance.approval_stage (
                    id, tenant_id, approval_plan_id,
                    stage_ordinal, decision_mode, lifecycle_state,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                stage.id(),
                tenant.tenantId(),
                stage.approvalPlanId(),
                stage.ordinal(),
                stage.decisionMode().name(),
                stage.lifecycleState().name(),
                Timestamp.from(stage.createdAt()),
                Timestamp.from(stage.updatedAt()));
    }

    @Override
    public void insertParticipant(
            TenantContext tenant,
            ApprovalParticipant participant) {
        jdbc.update("""
                INSERT INTO governance.approval_participant (
                    id, tenant_id, approval_stage_id,
                    approver_identity_id, participant_ordinal,
                    created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                participant.id(),
                tenant.tenantId(),
                participant.approvalStageId(),
                participant.approverIdentityId(),
                participant.ordinal(),
                Timestamp.from(participant.createdAt()));
    }

    @Override
    public Optional<ApprovalPlan> findPlan(
            TenantContext tenant, UUID planId) {
        return jdbc.query("""
                SELECT id, subject_kind, subject_id,
                       lifecycle_state, current_stage_ordinal,
                       deadline_at, revision, created_at,
                       updated_at, completed_at
                FROM governance.approval_plan
                WHERE tenant_id = ? AND id = ?
                """,
                (rs,row) -> plan(rs),
                tenant.tenantId(),
                planId)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<ApprovalPlan> findPendingBySubject(
            TenantContext tenant,
            ApprovalSubject subject) {
        return jdbc.query("""
                SELECT id, subject_kind, subject_id,
                       lifecycle_state, current_stage_ordinal,
                       deadline_at, revision, created_at,
                       updated_at, completed_at
                FROM governance.approval_plan
                WHERE tenant_id = ?
                  AND subject_kind = ?
                  AND subject_id = ?
                  AND lifecycle_state = 'PENDING'
                """,
                (rs,row) -> plan(rs),
                tenant.tenantId(),
                subject.kind().name(),
                subject.id())
                .stream()
                .findFirst();
    }

    @Override
    public List<ApprovalStage> findStages(
            TenantContext tenant, UUID planId) {
        return jdbc.query("""
                SELECT id, approval_plan_id, stage_ordinal,
                       decision_mode, lifecycle_state,
                       created_at, updated_at
                FROM governance.approval_stage
                WHERE tenant_id = ?
                  AND approval_plan_id = ?
                ORDER BY stage_ordinal
                """,
                (rs,row) -> stage(rs),
                tenant.tenantId(),
                planId);
    }

    @Override
    public Optional<ApprovalStage> findStage(
            TenantContext tenant,
            UUID planId,
            int ordinal) {
        return jdbc.query("""
                SELECT id, approval_plan_id, stage_ordinal,
                       decision_mode, lifecycle_state,
                       created_at, updated_at
                FROM governance.approval_stage
                WHERE tenant_id = ?
                  AND approval_plan_id = ?
                  AND stage_ordinal = ?
                """,
                (rs,row) -> stage(rs),
                tenant.tenantId(),
                planId,
                ordinal)
                .stream()
                .findFirst();
    }

    @Override
    public List<ApprovalParticipant> findParticipants(
            TenantContext tenant, UUID stageId) {
        return jdbc.query("""
                SELECT id, approval_stage_id,
                       approver_identity_id, participant_ordinal,
                       created_at
                FROM governance.approval_participant
                WHERE tenant_id = ?
                  AND approval_stage_id = ?
                ORDER BY participant_ordinal
                """,
                (rs,row) -> new ApprovalParticipant(
                        rs.getObject("id", UUID.class),
                        rs.getObject(
                                "approval_stage_id",
                                UUID.class),
                        rs.getObject(
                                "approver_identity_id",
                                UUID.class),
                        rs.getInt("participant_ordinal"),
                        rs.getTimestamp(
                                "created_at").toInstant()),
                tenant.tenantId(),
                stageId);
    }

    @Override
    public Optional<ApprovalDecision> findDecision(
            TenantContext tenant,
            UUID stageId,
            UUID approverIdentityId) {
        return jdbc.query("""
                SELECT id, approval_plan_id, approval_stage_id,
                       approver_identity_id, decision, reason,
                       correlation_id, causation_id, decided_at
                FROM governance.approval_decision
                WHERE tenant_id = ?
                  AND approval_stage_id = ?
                  AND approver_identity_id = ?
                """,
                (rs,row) -> decision(rs),
                tenant.tenantId(),
                stageId,
                approverIdentityId)
                .stream()
                .findFirst();
    }

    @Override
    public List<ApprovalDecision> findDecisions(
            TenantContext tenant, UUID stageId) {
        return jdbc.query("""
                SELECT id, approval_plan_id, approval_stage_id,
                       approver_identity_id, decision, reason,
                       correlation_id, causation_id, decided_at
                FROM governance.approval_decision
                WHERE tenant_id = ?
                  AND approval_stage_id = ?
                ORDER BY decided_at, id
                """,
                (rs,row) -> decision(rs),
                tenant.tenantId(),
                stageId);
    }

    @Override
    public void insertDecision(
            TenantContext tenant,
            ApprovalDecision decision) {
        jdbc.update("""
                INSERT INTO governance.approval_decision (
                    id, tenant_id, approval_plan_id,
                    approval_stage_id, approver_identity_id,
                    decision, reason, correlation_id,
                    causation_id, decided_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                decision.id(),
                tenant.tenantId(),
                decision.approvalPlanId(),
                decision.approvalStageId(),
                decision.approverIdentityId(),
                decision.value().name(),
                decision.reason(),
                decision.correlationId(),
                decision.causationId(),
                Timestamp.from(decision.decidedAt()));
    }

    @Override
    public void updateStageState(
            TenantContext tenant,
            UUID stageId,
            ApprovalStage.LifecycleState state,
            Instant now) {
        int affected = jdbc.update("""
                UPDATE governance.approval_stage
                SET lifecycle_state = ?, updated_at = ?
                WHERE tenant_id = ? AND id = ?
                """,
                state.name(),
                Timestamp.from(now),
                tenant.tenantId(),
                stageId);
        if (affected != 1) {
            throw new IllegalStateException(
                    "ApprovalStage did not update");
        }
    }

    @Override
    public ApprovalPlan updatePlan(
            TenantContext tenant,
            UUID planId,
            ApprovalPlan.LifecycleState state,
            int currentStageOrdinal,
            long expectedRevision,
            Instant now,
            Instant completedAt) {
        int affected = jdbc.update("""
                UPDATE governance.approval_plan
                SET lifecycle_state = ?,
                    current_stage_ordinal = ?,
                    revision = revision + 1,
                    updated_at = ?,
                    completed_at = ?
                WHERE tenant_id = ?
                  AND id = ?
                  AND revision = ?
                """,
                state.name(),
                currentStageOrdinal,
                Timestamp.from(now),
                nullableTimestamp(completedAt),
                tenant.tenantId(),
                planId,
                expectedRevision);
        if (affected != 1) {
            ApprovalPlan current = findPlan(
                            tenant, planId)
                    .orElseThrow(() ->
                            new IllegalStateException(
                                    "ApprovalPlan does not exist"));
            if (current.revision() != expectedRevision) {
                throw new StaleWriteException(
                        "approval-plan",
                        planId,
                        expectedRevision);
            }
            throw new IllegalStateException(
                    "ApprovalPlan did not update");
        }
        return findPlan(tenant, planId).orElseThrow();
    }

    @Override
    public void skipStagesAfter(
            TenantContext tenant,
            UUID planId,
            int ordinal,
            Instant now) {
        jdbc.update("""
                UPDATE governance.approval_stage
                SET lifecycle_state = 'SKIPPED',
                    updated_at = ?
                WHERE tenant_id = ?
                  AND approval_plan_id = ?
                  AND stage_ordinal > ?
                  AND lifecycle_state IN ('WAITING','ACTIVE')
                """,
                Timestamp.from(now),
                tenant.tenantId(),
                planId,
                ordinal);
    }

    private static ApprovalPlan plan(
            java.sql.ResultSet rs)
            throws java.sql.SQLException {
        Timestamp deadline =
                rs.getTimestamp("deadline_at");
        Timestamp completed =
                rs.getTimestamp("completed_at");
        return new ApprovalPlan(
                rs.getObject("id", UUID.class),
                new ApprovalSubject(
                        ApprovalSubject.Kind.valueOf(
                                rs.getString("subject_kind")),
                        rs.getObject(
                                "subject_id",
                                UUID.class)),
                ApprovalPlan.LifecycleState.valueOf(
                        rs.getString("lifecycle_state")),
                rs.getInt("current_stage_ordinal"),
                deadline == null
                        ? null
                        : deadline.toInstant(),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                completed == null
                        ? null
                        : completed.toInstant());
    }

    private static ApprovalStage stage(
            java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new ApprovalStage(
                rs.getObject("id", UUID.class),
                rs.getObject(
                        "approval_plan_id",
                        UUID.class),
                rs.getInt("stage_ordinal"),
                ApprovalStage.DecisionMode.valueOf(
                        rs.getString("decision_mode")),
                ApprovalStage.LifecycleState.valueOf(
                        rs.getString("lifecycle_state")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static ApprovalDecision decision(
            java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new ApprovalDecision(
                rs.getObject("id", UUID.class),
                rs.getObject(
                        "approval_plan_id",
                        UUID.class),
                rs.getObject(
                        "approval_stage_id",
                        UUID.class),
                rs.getObject(
                        "approver_identity_id",
                        UUID.class),
                ApprovalDecision.Value.valueOf(
                        rs.getString("decision")),
                rs.getString("reason"),
                rs.getObject(
                        "correlation_id",
                        UUID.class),
                rs.getObject(
                        "causation_id",
                        UUID.class),
                rs.getTimestamp(
                        "decided_at").toInstant());
    }

    private static Timestamp nullableTimestamp(
            Instant value) {
        return value == null
                ? null
                : Timestamp.from(value);
    }
}
