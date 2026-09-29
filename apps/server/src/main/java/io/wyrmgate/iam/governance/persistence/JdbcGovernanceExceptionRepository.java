package io.wyrmgate.iam.governance.persistence;

import io.wyrmgate.iam.governance.application.GovernanceExceptionRepository;
import io.wyrmgate.iam.governance.domain.GovernanceExceptionModels.*;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcGovernanceExceptionRepository
        implements GovernanceExceptionRepository {

    private final JdbcTemplate jdbc;

    public JdbcGovernanceExceptionRepository(
            JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(
            TenantContext tenant,
            GovernanceException exception) {
        jdbc.update("""
                INSERT INTO governance.governance_exception (
                    id, tenant_id, scope_kind, subject_identity_id,
                    sod_rule_id, requester_identity_id, business_reason,
                    valid_from, valid_until, lifecycle_state,
                    approval_case_id, predecessor_exception_id,
                    revision, created_at, updated_at,
                    approved_at, rejected_at, revoked_at, expired_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                exception.id(),
                tenant.tenantId(),
                exception.scopeKind().name(),
                exception.subjectIdentityId(),
                exception.sodRuleId(),
                exception.requesterIdentityId(),
                exception.businessReason(),
                Timestamp.from(exception.validFrom()),
                Timestamp.from(exception.validUntil()),
                exception.lifecycleState().name(),
                exception.approvalCaseId(),
                exception.predecessorExceptionId(),
                exception.revision(),
                Timestamp.from(exception.createdAt()),
                Timestamp.from(exception.updatedAt()),
                timestamp(exception.approvedAt()),
                timestamp(exception.rejectedAt()),
                timestamp(exception.revokedAt()),
                timestamp(exception.expiredAt()));
    }

    @Override
    public Optional<GovernanceException> findById(
            TenantContext tenant,
            UUID exceptionId) {
        return jdbc.query("""
                SELECT id, scope_kind, subject_identity_id, sod_rule_id,
                       requester_identity_id, business_reason,
                       valid_from, valid_until, lifecycle_state,
                       approval_case_id, predecessor_exception_id,
                       revision, created_at, updated_at,
                       approved_at, rejected_at, revoked_at, expired_at
                FROM governance.governance_exception
                WHERE tenant_id = ? AND id = ?
                """,
                (rs,row) -> row(rs),
                tenant.tenantId(),
                exceptionId)
                .stream()
                .findFirst();
    }

    @Override
    public List<GovernanceException> findEffective(
            TenantContext tenant,
            UUID subjectIdentityId,
            Set<UUID> sodRuleIds,
            Instant at) {
        if (sodRuleIds == null || sodRuleIds.isEmpty()) {
            return List.of();
        }
        if (sodRuleIds.size() > 2000) {
            throw new IllegalArgumentException(
                    "bounded exception query supports at most 2000 SoD rule IDs");
        }
        String placeholders = String.join(
                ",",
                java.util.Collections.nCopies(
                        sodRuleIds.size(), "?"));
        String sql = """
                SELECT id, scope_kind, subject_identity_id, sod_rule_id,
                       requester_identity_id, business_reason,
                       valid_from, valid_until, lifecycle_state,
                       approval_case_id, predecessor_exception_id,
                       revision, created_at, updated_at,
                       approved_at, rejected_at, revoked_at, expired_at
                FROM governance.governance_exception
                WHERE tenant_id = ?
                  AND subject_identity_id = ?
                  AND sod_rule_id IN (%s)
                  AND lifecycle_state = 'APPROVED'
                  AND valid_from <= ?
                  AND valid_until > ?
                ORDER BY sod_rule_id, valid_until, id
                """.formatted(placeholders);
        List<Object> args = new ArrayList<>();
        args.add(tenant.tenantId());
        args.add(subjectIdentityId);
        args.addAll(sodRuleIds);
        args.add(Timestamp.from(at));
        args.add(Timestamp.from(at));
        return jdbc.query(
                sql,
                (rs,row) -> row(rs),
                args.toArray());
    }

    @Override
    public GovernanceException updateState(
            TenantContext tenant,
            UUID exceptionId,
            LifecycleState state,
            long expectedRevision,
            Instant now,
            Instant approvedAt,
            Instant rejectedAt,
            Instant revokedAt,
            Instant expiredAt) {
        int affected = jdbc.update("""
                UPDATE governance.governance_exception
                SET lifecycle_state = ?,
                    revision = revision + 1,
                    updated_at = ?,
                    approved_at = ?,
                    rejected_at = ?,
                    revoked_at = ?,
                    expired_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ?
                """,
                state.name(),
                Timestamp.from(now),
                timestamp(approvedAt),
                timestamp(rejectedAt),
                timestamp(revokedAt),
                timestamp(expiredAt),
                tenant.tenantId(),
                exceptionId,
                expectedRevision);
        if (affected != 1) {
            if (findById(tenant, exceptionId).isEmpty()) {
                throw new IllegalArgumentException(
                        "GovernanceException does not exist");
            }
            throw new StaleWriteException(
                    "governance-exception",
                    exceptionId,
                    expectedRevision);
        }
        return findById(tenant, exceptionId)
                .orElseThrow();
    }

    private static GovernanceException row(
            ResultSet rs)
            throws SQLException {
        return new GovernanceException(
                rs.getObject("id", UUID.class),
                ScopeKind.valueOf(
                        rs.getString("scope_kind")),
                rs.getObject(
                        "subject_identity_id",
                        UUID.class),
                rs.getObject("sod_rule_id", UUID.class),
                rs.getObject(
                        "requester_identity_id",
                        UUID.class),
                rs.getString("business_reason"),
                rs.getTimestamp("valid_from").toInstant(),
                rs.getTimestamp("valid_until").toInstant(),
                LifecycleState.valueOf(
                        rs.getString("lifecycle_state")),
                rs.getObject(
                        "approval_case_id",
                        UUID.class),
                rs.getObject(
                        "predecessor_exception_id",
                        UUID.class),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                instant(rs.getTimestamp("approved_at")),
                instant(rs.getTimestamp("rejected_at")),
                instant(rs.getTimestamp("revoked_at")),
                instant(rs.getTimestamp("expired_at")));
    }

    private static Timestamp timestamp(
            Instant value) {
        return value == null
                ? null
                : Timestamp.from(value);
    }

    private static Instant instant(
            Timestamp value) {
        return value == null
                ? null
                : value.toInstant();
    }
}
