package io.wyrmgate.iam.governance.persistence;

import io.wyrmgate.iam.governance.application.ApprovalPlanSpec;
import io.wyrmgate.iam.governance.application.ApprovalRepository;
import io.wyrmgate.iam.governance.domain.ApprovalCase;
import io.wyrmgate.iam.governance.domain.ApprovalDecision;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
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
            TenantContext tenant, ApprovalCase approvalCase) {
        jdbc.update("""
                INSERT INTO governance.approval_case (
                    id, tenant_id, subject_type, subject_id, subject_revision,
                    requester_identity_id, lifecycle_state, current_stage_ordinal,
                    revision, created_at, updated_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                approvalCase.id(),
                tenant.tenantId(),
                approvalCase.subjectType().name(),
                approvalCase.subjectId(),
                approvalCase.subjectRevision(),
                approvalCase.requesterIdentityId(),
                approvalCase.lifecycleState().name(),
                approvalCase.currentStageOrdinal(),
                approvalCase.revision(),
                Timestamp.from(approvalCase.createdAt()),
                Timestamp.from(approvalCase.updatedAt()),
                timestamp(approvalCase.completedAt()));
    }

    @Override
    public void insertPlan(
            TenantContext tenant,
            UUID planId,
            UUID approvalCaseId,
            ApprovalPlanSpec.SelfApprovalPolicy selfApprovalPolicy,
            Instant createdAt) {
        jdbc.update("""
                INSERT INTO governance.approval_plan (
                    id, tenant_id, approval_case_id,
                    self_approval_policy, created_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                planId,
                tenant.tenantId(),
                approvalCaseId,
                selfApprovalPolicy.name(),
                Timestamp.from(createdAt));
    }

    @Override
    public void insertStage(
            TenantContext tenant,
            UUID stageId,
            UUID planId,
            int stageOrdinal,
            ApprovalPlanSpec.DecisionMode decisionMode,
            Instant createdAt) {
        jdbc.update("""
                INSERT INTO governance.approval_stage (
                    id, tenant_id, approval_plan_id,
                    stage_ordinal, decision_mode, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                stageId,
                tenant.tenantId(),
                planId,
                stageOrdinal,
                decisionMode.name(),
                Timestamp.from(createdAt));
    }

    @Override
    public void insertParticipant(
            TenantContext tenant,
            UUID participantId,
            UUID stageId,
            UUID identityId,
            Instant createdAt) {
        jdbc.update("""
                INSERT INTO governance.approval_participant (
                    id, tenant_id, approval_stage_id,
                    identity_id, created_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                participantId,
                tenant.tenantId(),
                stageId,
                identityId,
                Timestamp.from(createdAt));
    }

    @Override
    public Optional<ApprovalCase> findCase(
            TenantContext tenant, UUID approvalCaseId) {
        return jdbc.query("""
                SELECT id, subject_type, subject_id, subject_revision,
                       requester_identity_id, lifecycle_state,
                       current_stage_ordinal, revision,
                       created_at, updated_at, completed_at
                FROM governance.approval_case
                WHERE tenant_id = ? AND id = ?
                """,
                (rs,row) -> approvalCase(rs),
                tenant.tenantId(),
                approvalCaseId)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<ApprovalCase> findPendingCaseBySubject(
            TenantContext tenant,
            ApprovalCase.SubjectType subjectType,
            UUID subjectId) {
        return jdbc.query("""
                SELECT id, subject_type, subject_id, subject_revision,
                       requester_identity_id, lifecycle_state,
                       current_stage_ordinal, revision,
                       created_at, updated_at, completed_at
                FROM governance.approval_case
                WHERE tenant_id = ?
                  AND subject_type = ?
                  AND subject_id = ?
                  AND lifecycle_state = 'PENDING'
                """,
                (rs,row) -> approvalCase(rs),
                tenant.tenantId(),
                subjectType.name(),
                subjectId)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<PlanSnapshot> findPlanByCase(
            TenantContext tenant, UUID approvalCaseId) {
        return jdbc.query("""
                SELECT p.id, p.self_approval_policy,
                       count(s.id) AS stage_count
                FROM governance.approval_plan p
                LEFT JOIN governance.approval_stage s
                  ON s.tenant_id = p.tenant_id
                 AND s.approval_plan_id = p.id
                WHERE p.tenant_id = ?
                  AND p.approval_case_id = ?
                GROUP BY p.id, p.self_approval_policy
                """,
                (rs,row) -> new PlanSnapshot(
                        rs.getObject("id", UUID.class),
                        ApprovalPlanSpec.SelfApprovalPolicy.valueOf(
                                rs.getString("self_approval_policy")),
                        rs.getInt("stage_count")),
                tenant.tenantId(),
                approvalCaseId)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<StageSnapshot> findStage(
            TenantContext tenant,
            UUID approvalCaseId,
            int stageOrdinal) {
        record Base(
                UUID stageId,
                ApprovalPlanSpec.DecisionMode decisionMode) {}
        Optional<Base> base = jdbc.query("""
                SELECT s.id, s.decision_mode
                FROM governance.approval_stage s
                JOIN governance.approval_plan p
                  ON p.tenant_id = s.tenant_id
                 AND p.id = s.approval_plan_id
                WHERE s.tenant_id = ?
                  AND p.approval_case_id = ?
                  AND s.stage_ordinal = ?
                """,
                (rs,row) -> new Base(
                        rs.getObject("id", UUID.class),
                        ApprovalPlanSpec.DecisionMode.valueOf(
                                rs.getString("decision_mode"))),
                tenant.tenantId(),
                approvalCaseId,
                stageOrdinal)
                .stream()
                .findFirst();
        if (base.isEmpty()) return Optional.empty();

        List<UUID> participants = jdbc.query("""
                SELECT identity_id
                FROM governance.approval_participant
                WHERE tenant_id = ?
                  AND approval_stage_id = ?
                ORDER BY identity_id
                """,
                (rs,row) -> rs.getObject("identity_id", UUID.class),
                tenant.tenantId(),
                base.get().stageId());
        return Optional.of(new StageSnapshot(
                base.get().stageId(),
                stageOrdinal,
                base.get().decisionMode(),
                participants));
    }

    @Override
    public List<ApprovalDecision> findStageDecisions(
            TenantContext tenant,
            UUID approvalCaseId,
            UUID approvalStageId) {
        return jdbc.query("""
                SELECT id, approval_case_id, approval_stage_id,
                       participant_identity_id, decision,
                       decided_at, correlation_id, causation_id
                FROM governance.approval_decision
                WHERE tenant_id = ?
                  AND approval_case_id = ?
                  AND approval_stage_id = ?
                ORDER BY decided_at, id
                """,
                (rs,row) -> decision(rs),
                tenant.tenantId(),
                approvalCaseId,
                approvalStageId);
    }

    @Override
    public Optional<ApprovalDecision> findDecision(
            TenantContext tenant,
            UUID approvalCaseId,
            UUID approvalStageId,
            UUID participantIdentityId) {
        return jdbc.query("""
                SELECT id, approval_case_id, approval_stage_id,
                       participant_identity_id, decision,
                       decided_at, correlation_id, causation_id
                FROM governance.approval_decision
                WHERE tenant_id = ?
                  AND approval_case_id = ?
                  AND approval_stage_id = ?
                  AND participant_identity_id = ?
                """,
                (rs,row) -> decision(rs),
                tenant.tenantId(),
                approvalCaseId,
                approvalStageId,
                participantIdentityId)
                .stream()
                .findFirst();
    }

    @Override
    public void insertDecision(
            TenantContext tenant, ApprovalDecision decision) {
        jdbc.update("""
                INSERT INTO governance.approval_decision (
                    id, tenant_id, approval_case_id, approval_stage_id,
                    participant_identity_id, decision, decided_at,
                    correlation_id, causation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                decision.id(),
                tenant.tenantId(),
                decision.approvalCaseId(),
                decision.approvalStageId(),
                decision.participantIdentityId(),
                decision.decision().name(),
                Timestamp.from(decision.decidedAt()),
                decision.correlationId(),
                decision.causationId());
    }

    @Override
    public ApprovalCase updateCase(
            TenantContext tenant,
            UUID approvalCaseId,
            long expectedRevision,
            ApprovalCase.LifecycleState lifecycleState,
            int currentStageOrdinal,
            Instant now,
            Instant completedAt) {
        int affected = jdbc.update("""
                UPDATE governance.approval_case
                SET lifecycle_state = ?,
                    current_stage_ordinal = ?,
                    revision = revision + 1,
                    updated_at = ?,
                    completed_at = ?
                WHERE tenant_id = ?
                  AND id = ?
                  AND revision = ?
                """,
                lifecycleState.name(),
                currentStageOrdinal,
                Timestamp.from(now),
                timestamp(completedAt),
                tenant.tenantId(),
                approvalCaseId,
                expectedRevision);
        if (affected != 1) {
            ApprovalCase current = findCase(tenant, approvalCaseId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "approval case does not exist"));
            if (current.revision() != expectedRevision) {
                throw new StaleWriteException(
                        "approval-case",
                        approvalCaseId,
                        expectedRevision);
            }
            throw new IllegalStateException(
                    "approval case did not update");
        }
        return findCase(tenant, approvalCaseId).orElseThrow();
    }

    @Override
    public List<InboxItem> findInboxPage(
            TenantContext tenant,
            UUID participantIdentityId,
            Instant afterCreatedAt,
            UUID afterCaseId,
            int limit) {
        String after = afterCreatedAt == null || afterCaseId == null
                ? ""
                : " AND (c.created_at, c.id) > (?, ?) ";
        String sql = """
                SELECT c.id, c.subject_type, c.subject_id, c.subject_revision,
                       c.requester_identity_id, c.lifecycle_state,
                       c.current_stage_ordinal, c.revision,
                       c.created_at, c.updated_at, c.completed_at
                FROM governance.approval_case c
                JOIN governance.approval_plan p
                  ON p.tenant_id = c.tenant_id
                 AND p.approval_case_id = c.id
                JOIN governance.approval_stage s
                  ON s.tenant_id = p.tenant_id
                 AND s.approval_plan_id = p.id
                 AND s.stage_ordinal = c.current_stage_ordinal
                JOIN governance.approval_participant ap
                  ON ap.tenant_id = s.tenant_id
                 AND ap.approval_stage_id = s.id
                WHERE c.tenant_id = ?
                  AND c.lifecycle_state = 'PENDING'
                  AND ap.identity_id = ?
                  AND NOT EXISTS (
                      SELECT 1
                      FROM governance.approval_decision d
                      WHERE d.tenant_id = c.tenant_id
                        AND d.approval_case_id = c.id
                        AND d.approval_stage_id = s.id
                        AND d.participant_identity_id = ?
                  )
                """ + after + """
                ORDER BY c.created_at, c.id
                LIMIT ?
                """;
        List<Object> args = new ArrayList<>();
        args.add(tenant.tenantId());
        args.add(participantIdentityId);
        args.add(participantIdentityId);
        if (!after.isEmpty()) {
            args.add(Timestamp.from(afterCreatedAt));
            args.add(afterCaseId);
        }
        args.add(limit);

        List<ApprovalCase> cases = jdbc.query(
                sql,
                (rs,row) -> approvalCase(rs),
                args.toArray());
        List<InboxItem> result = new ArrayList<>();
        for (ApprovalCase value : cases) {
            StageSnapshot stage = findStage(
                    tenant,
                    value.id(),
                    value.currentStageOrdinal())
                    .orElseThrow();
            result.add(new InboxItem(
                    value,
                    stage.stageId(),
                    stage.decisionMode(),
                    stage.participantIdentityIds()));
        }
        return List.copyOf(result);
    }

    private static ApprovalCase approvalCase(
            ResultSet rs) throws SQLException {
        Timestamp completed = rs.getTimestamp("completed_at");
        return new ApprovalCase(
                rs.getObject("id", UUID.class),
                ApprovalCase.SubjectType.valueOf(
                        rs.getString("subject_type")),
                rs.getObject("subject_id", UUID.class),
                rs.getLong("subject_revision"),
                rs.getObject("requester_identity_id", UUID.class),
                ApprovalCase.LifecycleState.valueOf(
                        rs.getString("lifecycle_state")),
                rs.getInt("current_stage_ordinal"),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                completed == null ? null : completed.toInstant());
    }

    private static ApprovalDecision decision(
            ResultSet rs) throws SQLException {
        return new ApprovalDecision(
                rs.getObject("id", UUID.class),
                rs.getObject("approval_case_id", UUID.class),
                rs.getObject("approval_stage_id", UUID.class),
                rs.getObject("participant_identity_id", UUID.class),
                ApprovalDecision.Decision.valueOf(
                        rs.getString("decision")),
                rs.getTimestamp("decided_at").toInstant(),
                rs.getObject("correlation_id", UUID.class),
                rs.getObject("causation_id", UUID.class));
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
