package io.wyrmgate.iam.governance.persistence;

import io.wyrmgate.iam.governance.application.GovernanceSubjectQuery;
import io.wyrmgate.iam.governance.domain.GovernanceExceptionModels.*;
import io.wyrmgate.iam.governance.domain.ReviewModels.*;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public final class JdbcGovernanceSubjectQuery implements GovernanceSubjectQuery {
    private final JdbcTemplate jdbc;

    public JdbcGovernanceSubjectQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public CampaignPage campaigns(TenantContext tenant, UUID subjectIdentityId, Position after, int limit) {
        requireLimit(limit);
        List<ReviewCampaign> rows = jdbc.query("""
                SELECT id, campaign_kind, subject_identity_id,
                       reviewer_identity_id, snapshot_at, lifecycle_state,
                       generation_after_created_at, generation_after_id,
                       generated_item_count, decided_item_count, revision,
                       created_at, updated_at, activated_at, completed_at, failure_code
                FROM governance.review_campaign
                WHERE tenant_id = ? AND subject_identity_id = ?
                  AND (?::timestamptz IS NULL OR created_at > ? OR (created_at = ? AND id > ?))
                ORDER BY created_at, id
                LIMIT ?
                """, (rs,row) -> campaign(rs), tenant.tenantId(), subjectIdentityId,
                timestamp(after == null ? null : after.createdAt()),
                timestamp(after == null ? null : after.createdAt()),
                timestamp(after == null ? null : after.createdAt()),
                after == null ? null : after.id(), limit + 1);
        boolean more = rows.size() > limit;
        List<ReviewCampaign> selected = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        Position next = more && !selected.isEmpty() ? new Position(selected.getLast().createdAt(), selected.getLast().id()) : null;
        return new CampaignPage(selected, next);
    }

    @Override
    public ExceptionPage exceptions(TenantContext tenant, UUID subjectIdentityId, Position after, int limit) {
        requireLimit(limit);
        List<GovernanceException> rows = jdbc.query("""
                SELECT id, scope_kind, subject_identity_id, sod_rule_id,
                       requester_identity_id, business_reason, valid_from, valid_until,
                       lifecycle_state, approval_case_id, predecessor_exception_id,
                       revision, created_at, updated_at, approved_at, rejected_at,
                       revoked_at, expired_at
                FROM governance.governance_exception
                WHERE tenant_id = ? AND subject_identity_id = ?
                  AND (?::timestamptz IS NULL OR created_at > ? OR (created_at = ? AND id > ?))
                ORDER BY created_at, id
                LIMIT ?
                """, (rs,row) -> exception(rs), tenant.tenantId(), subjectIdentityId,
                timestamp(after == null ? null : after.createdAt()),
                timestamp(after == null ? null : after.createdAt()),
                timestamp(after == null ? null : after.createdAt()),
                after == null ? null : after.id(), limit + 1);
        boolean more = rows.size() > limit;
        List<GovernanceException> selected = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        Position next = more && !selected.isEmpty() ? new Position(selected.getLast().createdAt(), selected.getLast().id()) : null;
        return new ExceptionPage(selected, next);
    }

    private static ReviewCampaign campaign(ResultSet rs) throws SQLException {
        return new ReviewCampaign(
                rs.getObject("id", UUID.class), CampaignKind.valueOf(rs.getString("campaign_kind")),
                rs.getObject("subject_identity_id", UUID.class), rs.getObject("reviewer_identity_id", UUID.class),
                rs.getTimestamp("snapshot_at").toInstant(), CampaignState.valueOf(rs.getString("lifecycle_state")),
                instant(rs.getTimestamp("generation_after_created_at")), rs.getObject("generation_after_id", UUID.class),
                rs.getLong("generated_item_count"), rs.getLong("decided_item_count"), rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                instant(rs.getTimestamp("activated_at")), instant(rs.getTimestamp("completed_at")), rs.getString("failure_code"));
    }

    private static GovernanceException exception(ResultSet rs) throws SQLException {
        return new GovernanceException(
                rs.getObject("id", UUID.class), ScopeKind.valueOf(rs.getString("scope_kind")),
                rs.getObject("subject_identity_id", UUID.class), rs.getObject("sod_rule_id", UUID.class),
                rs.getObject("requester_identity_id", UUID.class), rs.getString("business_reason"),
                rs.getTimestamp("valid_from").toInstant(), rs.getTimestamp("valid_until").toInstant(),
                LifecycleState.valueOf(rs.getString("lifecycle_state")), rs.getObject("approval_case_id", UUID.class),
                rs.getObject("predecessor_exception_id", UUID.class), rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                instant(rs.getTimestamp("approved_at")), instant(rs.getTimestamp("rejected_at")),
                instant(rs.getTimestamp("revoked_at")), instant(rs.getTimestamp("expired_at")));
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit must be between 1 and 200");
    }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
