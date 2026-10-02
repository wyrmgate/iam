package io.wyrmgate.iam.audit.persistence;

import io.wyrmgate.iam.audit.application.EvidenceSnapshotRepository;
import io.wyrmgate.iam.audit.domain.EvidenceResourceReference;
import io.wyrmgate.iam.audit.domain.EvidenceSnapshot;
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

public final class JdbcEvidenceSnapshotRepository implements EvidenceSnapshotRepository {

    private final JdbcTemplate jdbc;

    public JdbcEvidenceSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public boolean insertIfAbsent(TenantContext tenant, EvidenceSnapshot snapshot) {
        return jdbc.update(
                """
                INSERT INTO audit.evidence_snapshot (
                    id, tenant_id, occurred_at, recorded_at, actor_id, snapshot_type,
                    subject_resource_type, subject_resource_id, subject_revision, subject_display_label,
                    policy_resource_type, policy_resource_id, policy_revision, policy_display_label,
                    related_resource_type, related_resource_id, related_revision, related_display_label,
                    decision_label, correlation_id, causation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """,
                snapshot.id(),
                tenant.tenantId(),
                Timestamp.from(snapshot.occurredAt()),
                Timestamp.from(snapshot.recordedAt()),
                snapshot.actorId(),
                snapshot.snapshotType(),
                snapshot.subject().resourceType(),
                snapshot.subject().resourceId(),
                snapshot.subject().revision(),
                snapshot.subject().displayLabel(),
                type(snapshot.policy()),
                id(snapshot.policy()),
                revision(snapshot.policy()),
                label(snapshot.policy()),
                type(snapshot.related()),
                id(snapshot.related()),
                revision(snapshot.related()),
                label(snapshot.related()),
                snapshot.decisionLabel(),
                snapshot.correlationId(),
                snapshot.causationId()) == 1;
    }

    @Override
    public Optional<EvidenceSnapshot> findById(TenantContext tenant, UUID id) {
        return jdbc.query(
                        select() + " WHERE tenant_id = ? AND id = ?",
                        (rs, rowNum) -> row(rs),
                        tenant.tenantId(),
                        id)
                .stream()
                .findFirst();
    }

    @Override
    public List<EvidenceSnapshot> findPage(
            TenantContext tenant,
            String snapshotType,
            String subjectResourceType,
            UUID subjectResourceId,
            UUID correlationId,
            Instant afterOccurredAt,
            UUID afterId,
            int limit) {
        if ((afterOccurredAt == null) != (afterId == null)) {
            throw new IllegalArgumentException("both EvidenceSnapshot continuation values are required");
        }
        StringBuilder sql = new StringBuilder(select() + " WHERE tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenant.tenantId());
        if (snapshotType != null) {
            sql.append(" AND snapshot_type = ?");
            args.add(snapshotType);
        }
        if (subjectResourceType != null) {
            sql.append(" AND subject_resource_type = ?");
            args.add(subjectResourceType);
        }
        if (subjectResourceId != null) {
            sql.append(" AND subject_resource_id = ?");
            args.add(subjectResourceId);
        }
        if (correlationId != null) {
            sql.append(" AND correlation_id = ?");
            args.add(correlationId);
        }
        if (afterOccurredAt != null) {
            sql.append(" AND (occurred_at < ? OR (occurred_at = ? AND id < ?))");
            args.add(Timestamp.from(afterOccurredAt));
            args.add(Timestamp.from(afterOccurredAt));
            args.add(afterId);
        }
        sql.append(" ORDER BY occurred_at DESC, id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, rowNum) -> row(rs), args.toArray());
    }

    private static String select() {
        return """
                SELECT id, occurred_at, recorded_at, actor_id, snapshot_type,
                       subject_resource_type, subject_resource_id, subject_revision, subject_display_label,
                       policy_resource_type, policy_resource_id, policy_revision, policy_display_label,
                       related_resource_type, related_resource_id, related_revision, related_display_label,
                       decision_label, correlation_id, causation_id
                FROM audit.evidence_snapshot
                """;
    }

    private static EvidenceSnapshot row(ResultSet rs) throws SQLException {
        return new EvidenceSnapshot(
                rs.getObject("id", UUID.class),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getTimestamp("recorded_at").toInstant(),
                rs.getObject("actor_id", UUID.class),
                rs.getString("snapshot_type"),
                reference(rs, "subject"),
                reference(rs, "policy"),
                reference(rs, "related"),
                rs.getString("decision_label"),
                rs.getObject("correlation_id", UUID.class),
                rs.getObject("causation_id", UUID.class));
    }

    private static EvidenceResourceReference reference(ResultSet rs, String prefix) throws SQLException {
        String resourceType = rs.getString(prefix + "_resource_type");
        if (resourceType == null) return null;
        Long revision = rs.getObject(prefix + "_revision", Long.class);
        return new EvidenceResourceReference(
                resourceType,
                rs.getObject(prefix + "_resource_id", UUID.class),
                revision,
                rs.getString(prefix + "_display_label"));
    }

    private static String type(EvidenceResourceReference ref) {
        return ref == null ? null : ref.resourceType();
    }

    private static UUID id(EvidenceResourceReference ref) {
        return ref == null ? null : ref.resourceId();
    }

    private static Long revision(EvidenceResourceReference ref) {
        return ref == null ? null : ref.revision();
    }

    private static String label(EvidenceResourceReference ref) {
        return ref == null ? null : ref.displayLabel();
    }
}
