package io.wyrmgate.iam.governance.persistence;

import io.wyrmgate.iam.governance.application.ApprovalModels.ApprovalApprover;
import io.wyrmgate.iam.governance.application.ApprovalModels.ApprovalCase;
import io.wyrmgate.iam.governance.application.ApprovalModels.ApprovalDecision;
import io.wyrmgate.iam.governance.application.ApprovalModels.ApprovalPlan;
import io.wyrmgate.iam.governance.application.ApprovalModels.ApprovalStage;
import io.wyrmgate.iam.governance.application.ApprovalModels.CaseState;
import io.wyrmgate.iam.governance.application.ApprovalModels.DecisionMode;
import io.wyrmgate.iam.governance.application.ApprovalModels.DecisionValue;
import io.wyrmgate.iam.governance.application.ApprovalModels.SubjectKind;
import io.wyrmgate.iam.governance.application.ApprovalRepository;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcApprovalRepository implements ApprovalRepository {

    private final JdbcTemplate jdbc;

    public JdbcApprovalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertCase(
            TenantContext tenant,
            ApprovalCase approvalCase) {
        jdbc.update("""
                INSERT INTO governance.approval_case (
                    id, tenant_id, subject_kind, subject_id,
                    initiator_identity_id, state, current_stage_ordinal,
                    revision, created_at, updated_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                approvalCase.id(),
                tenant.tenantId(),
                approvalCase.subjectKind().name(),
                approvalCase.subjectId(),
                approvalCase.initiatorIdentityId(),
                approvalCase.state().name(),
                approvalCase.currentStageOrdinal(),
                approvalCase.revision(),
                Timestamp.from(approvalCase.createdAt()),
                Timestamp.from(approvalCase.updatedAt()),
                approvalCase.completedAt() == null
                        ? null
                        : Timestamp.from(approvalCase.completedAt()));
    }

    @Override
    public void insertPlan(
            TenantContext tenant,
            ApprovalPlan plan) {
        jdbc.update("""
                INSERT INTO governance.approval_plan (
                    id, tenant_id, approval_case_id,
                    plan_number, content_hash, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                plan.id(),
                tenant.tenantId(),
                plan.approvalCaseId(),
                plan.planNumber(),
                plan.contentHash(),
                Timestamp.from(plan.createdAt()));
    }

    @Override
    public void insertStage(
            TenantContext tenant,
            ApprovalStage stage) {
        jdbc.update("""
                INSERT INTO governance.approval_stage (
                    id, tenant_id, approval_plan_id,
                    stage_ordinal, decision_mode, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                stage.id(),
                tenant.tenantId(),
                stage.approvalPlanId(),
                stage.ordinal(),
                stage.decisionMode().name(),
                Timestamp.from(stage.createdAt()));
    }

    @Override
    public void insertApprover(
            TenantContext tenant,
            ApprovalApprover approver) {
        jdbc.update("""
                INSERT INTO governance.approval_approver (
                    id, tenant_id, approval_stage_id,
                    approver_identity_id, created_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                approver.id(),
                tenant.tenantId(),
                approver.approvalStageId(),
                approver.approverIdentityId(),
                Timestamp.from(approver.createdAt()));
    }

    @Override
    public void insertDecision(
            TenantContext tenant,
            ApprovalDecision decision) {
        jdbc.update("""
                INSERT INTO governance.approval_decision (
                    id, tenant_id, approval_case_id, approval_plan_id,
                    approval_stage_id, approver_identity_id,
                    decision, reason, decided_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                decision.id(),
                tenant.tenantId(),
                decision.approvalCaseId(),
                decision.approvalPlanId(),
                decision.approvalStageId(),
                decision.approverIdentityId(),
                decision.decision().name(),
                decision.reason(),
                Timestamp.from(decision.decidedAt()));
    }

    @Override
    public Optional<ApprovalCase> findCase(
            TenantContext tenant,
            UUID caseId) {
        return jdbc.query("""
                SELECT id, subject_kind, subject_id,
                       initiator_identity_id, state,
                       current_stage_ordinal, revision,
                       created_at, updated_at, completed_at
                FROM governance.approval_case
                WHERE tenant_id = ? AND id = ?
                """,
                (rs,row) -> caseRow(rs),
                tenant.tenantId(),
                caseId)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<ApprovalCase> findPendingBySubject(
            TenantContext tenant,
            SubjectKind subjectKind,
            UUID subjectId) {
        return jdbc.query("""
                SELECT id, subject_kind, subject_id,
                       initiator_identity_id, state,
                       current_stage_ordinal, revision,
                       created_at, updated_at, completed_at
                FROM governance.approval_case
                WHERE tenant_id = ?
                  AND subject_kind = ?
                  AND subject_id = ?
                  AND state = 'PENDING'
                """,
                (rs,row) -> caseRow(rs),
                tenant.tenantId(),
                subjectKind.name(),
                subjectId)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<ApprovalCase> findLatestBySubject(
            TenantContext tenant,
            SubjectKind subjectKind,
            UUID subjectId) {
        return jdbc.query("""
                SELECT id, subject_kind, subject_id,
                       initiator_identity_id, state,
                       current_stage_ordinal, revision,
                       created_at, updated_at, completed_at
                FROM governance.approval_case
                WHERE tenant_id = ?
                  AND subject_kind = ?
                  AND subject_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT 1
                """,
                (rs,row) -> caseRow(rs),
                tenant.tenantId(),
                subjectKind.name(),
                subjectId)
                .stream()
                .findFirst();
    }

    @Override
    public ApprovalPlan findPlan(
            TenantContext tenant,
            UUID caseId) {
        return jdbc.query("""
                SELECT id, approval_case_id, plan_number,
                       content_hash, created_at
                FROM governance.approval_plan
                WHERE tenant_id = ? AND approval_case_id = ?
                ORDER BY plan_number DESC
                LIMIT 1
                """,
                (rs,row) -> new ApprovalPlan(
                        rs.getObject("id", UUID.class),
                        rs.getObject("approval_case_id", UUID.class),
                        rs.getLong("plan_number"),
                        rs.getString("content_hash"),
                        rs.getTimestamp("created_at").toInstant()),
                tenant.tenantId(),
                caseId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "approval plan does not exist"));
    }

    @Override
    public List<ApprovalStage> findStages(
            TenantContext tenant,
            UUID planId) {
        return jdbc.query("""
                SELECT id, approval_plan_id, stage_ordinal,
                       decision_mode, created_at
                FROM governance.approval_stage
                WHERE tenant_id = ? AND approval_plan_id = ?
                ORDER BY stage_ordinal
                """,
                (rs,row) -> new ApprovalStage(
                        rs.getObject("id", UUID.class),
                        rs.getObject("approval_plan_id", UUID.class),
                        rs.getInt("stage_ordinal"),
                        DecisionMode.valueOf(
                                rs.getString("decision_mode")),
                        rs.getTimestamp("created_at").toInstant()),
                tenant.tenantId(),
                planId);
    }

    @Override
    public List<ApprovalApprover> findApprovers(
            TenantContext tenant,
            UUID stageId) {
        return jdbc.query("""
                SELECT id, approval_stage_id,
                       approver_identity_id, created_at
                FROM governance.approval_approver
                WHERE tenant_id = ? AND approval_stage_id = ?
                ORDER BY approver_identity_id
                """,
                (rs,row) -> new ApprovalApprover(
                        rs.getObject("id", UUID.class),
                        rs.getObject("approval_stage_id", UUID.class),
                        rs.getObject("approver_identity_id", UUID.class),
                        rs.getTimestamp("created_at").toInstant()),
                tenant.tenantId(),
                stageId);
    }

    @Override
    public Optional<ApprovalDecision> findDecision(
            TenantContext tenant,
            UUID stageId,
            UUID approverIdentityId) {
        return jdbc.query("""
                SELECT id, approval_case_id, approval_plan_id,
                       approval_stage_id, approver_identity_id,
                       decision, reason, decided_at
                FROM governance.approval_decision
                WHERE tenant_id = ?
                  AND approval_stage_id = ?
                  AND approver_identity_id = ?
                """,
                (rs,row) -> decisionRow(rs),
                tenant.tenantId(),
                stageId,
                approverIdentityId)
                .stream()
                .findFirst();
    }

    @Override
    public List<ApprovalDecision> findDecisions(
            TenantContext tenant,
            UUID stageId) {
        return jdbc.query("""
                SELECT id, approval_case_id, approval_plan_id,
                       approval_stage_id, approver_identity_id,
                       decision, reason, decided_at
                FROM governance.approval_decision
                WHERE tenant_id = ?
                  AND approval_stage_id = ?
                ORDER BY decided_at, id
                """,
                (rs,row) -> decisionRow(rs),
                tenant.tenantId(),
                stageId);
    }

    @Override
    public ApprovalCase updateCase(
            TenantContext tenant,
            UUID caseId,
            CaseState state,
            int currentStageOrdinal,
            long expectedRevision,
            Instant now,
            Instant completedAt) {
        int affected = jdbc.update("""
                UPDATE governance.approval_case
                SET state = ?,
                    current_stage_ordinal = ?,
                    revision = revision + 1,
                    updated_at = ?,
                    completed_at = ?
                WHERE tenant_id = ?
                  AND id = ?
                  AND revision = ?
                  AND state = 'PENDING'
                """,
                state.name(),
                currentStageOrdinal,
                Timestamp.from(now),
                completedAt == null
                        ? null
                        : Timestamp.from(completedAt),
                tenant.tenantId(),
                caseId,
                expectedRevision);
        if (affected != 1) {
            ApprovalCase current = findCase(tenant, caseId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "approval case does not exist"));
            if (current.revision() != expectedRevision) {
                throw new StaleWriteException(
                        "approval-case",
                        caseId,
                        expectedRevision);
            }
            throw new IllegalStateException(
                    "approval case state did not update");
        }
        return findCase(tenant, caseId).orElseThrow();
    }

    private static ApprovalCase caseRow(ResultSet rs)
            throws SQLException {
        Timestamp completed = rs.getTimestamp("completed_at");
        return new ApprovalCase(
                rs.getObject("id", UUID.class),
                SubjectKind.valueOf(rs.getString("subject_kind")),
                rs.getObject("subject_id", UUID.class),
                rs.getObject("initiator_identity_id", UUID.class),
                CaseState.valueOf(rs.getString("state")),
                rs.getInt("current_stage_ordinal"),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                completed == null ? null : completed.toInstant());
    }

    private static ApprovalDecision decisionRow(ResultSet rs)
            throws SQLException {
        return new ApprovalDecision(
                rs.getObject("id", UUID.class),
                rs.getObject("approval_case_id", UUID.class),
                rs.getObject("approval_plan_id", UUID.class),
                rs.getObject("approval_stage_id", UUID.class),
                rs.getObject("approver_identity_id", UUID.class),
                DecisionValue.valueOf(rs.getString("decision")),
                rs.getString("reason"),
                rs.getTimestamp("decided_at").toInstant());
    }
}
