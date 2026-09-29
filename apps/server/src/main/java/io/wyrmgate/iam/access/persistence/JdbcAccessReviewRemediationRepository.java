package io.wyrmgate.iam.access.persistence;

import io.wyrmgate.iam.access.application.AccessReviewRemediationCommand;
import io.wyrmgate.iam.access.application.AccessReviewRemediationRepository;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcAccessReviewRemediationRepository
        implements AccessReviewRemediationRepository {

    private final JdbcTemplate jdbc;

    public JdbcAccessReviewRemediationRepository(
            JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AccessReviewRemediationCommand.Result> find(
            TenantContext tenant,
            UUID reviewRemediationId) {
        return jdbc.query("""
                SELECT access_assignment_id, outcome,
                       resulting_lifecycle_state
                FROM access.review_remediation_application
                WHERE tenant_id = ?
                  AND review_remediation_id = ?
                """,
                (rs,row) ->
                        new AccessReviewRemediationCommand.Result(
                                rs.getObject(
                                        "access_assignment_id",
                                        UUID.class),
                                AccessReviewRemediationCommand.Outcome
                                        .valueOf(
                                                rs.getString(
                                                        "outcome")),
                                rs.getString(
                                        "resulting_lifecycle_state")),
                tenant.tenantId(),
                reviewRemediationId)
                .stream()
                .findFirst();
    }

    @Override
    public void insert(
            TenantContext tenant,
            UUID reviewRemediationId,
            UUID accessAssignmentId,
            AccessReviewRemediationCommand.Result result,
            Instant appliedAt) {
        jdbc.update("""
                INSERT INTO access.review_remediation_application (
                    tenant_id, review_remediation_id,
                    access_assignment_id, outcome,
                    resulting_lifecycle_state, applied_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                tenant.tenantId(),
                reviewRemediationId,
                accessAssignmentId,
                result.outcome().name(),
                result.resultingLifecycleState(),
                Timestamp.from(appliedAt));
    }
}
