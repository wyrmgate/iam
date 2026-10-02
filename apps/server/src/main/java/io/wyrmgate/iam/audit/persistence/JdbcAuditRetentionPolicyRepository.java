package io.wyrmgate.iam.audit.persistence;

import io.wyrmgate.iam.audit.application.AuditRetentionPolicyRepository;
import io.wyrmgate.iam.audit.domain.AuditRetentionPolicyVersion;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcAuditRetentionPolicyRepository implements AuditRetentionPolicyRepository {

    private final JdbcTemplate jdbc;

    public JdbcAuditRetentionPolicyRepository(JdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public long nextVersion(TenantContext tenant) {
        jdbc.queryForObject(
                "SELECT id FROM platform.tenant WHERE id = ? FOR UPDATE",
                UUID.class,
                tenant.tenantId());
        Long current = jdbc.queryForObject(
                "SELECT COALESCE(MAX(policy_version), 0) FROM audit.audit_retention_policy_version WHERE tenant_id = ?",
                Long.class,
                tenant.tenantId());
        return java.util.Objects.requireNonNull(current) + 1L;
    }

    @Override
    public AuditRetentionPolicyVersion create(
            TenantContext tenant,
            UUID id,
            long version,
            Duration exportArtifactLifetime,
            Duration archiveEligibilityAge,
            Duration minimumOnlineRecordRetention,
            Duration minimumArchiveRetention,
            Instant effectiveFrom,
            UUID correlationId,
            UUID causationId,
            Instant createdAt) {
        jdbc.update(
                """
                INSERT INTO audit.audit_retention_policy_version (
                    id, tenant_id, policy_version,
                    export_artifact_lifetime_ms, archive_eligibility_age_ms,
                    minimum_online_record_retention_ms, minimum_archive_retention_ms,
                    effective_from, correlation_id, causation_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                tenant.tenantId(),
                version,
                millis(exportArtifactLifetime),
                millis(archiveEligibilityAge),
                millis(minimumOnlineRecordRetention),
                millis(minimumArchiveRetention),
                Timestamp.from(effectiveFrom),
                correlationId,
                causationId,
                Timestamp.from(createdAt));
        return findByVersion(tenant, version).orElseThrow();
    }

    @Override
    public Optional<AuditRetentionPolicyVersion> findEffective(
            TenantContext tenant,
            Instant at) {
        return jdbc.query(
                        """
                        SELECT id, policy_version,
                               export_artifact_lifetime_ms, archive_eligibility_age_ms,
                               minimum_online_record_retention_ms, minimum_archive_retention_ms,
                               effective_from, correlation_id, causation_id, created_at
                        FROM audit.audit_retention_policy_version
                        WHERE tenant_id = ? AND effective_from <= ?
                        ORDER BY effective_from DESC, policy_version DESC
                        LIMIT 1
                        """,
                        (rs, rowNum) -> row(rs),
                        tenant.tenantId(),
                        Timestamp.from(at))
                .stream()
                .findFirst();
    }

    private Optional<AuditRetentionPolicyVersion> findByVersion(
            TenantContext tenant,
            long version) {
        return jdbc.query(
                        """
                        SELECT id, policy_version,
                               export_artifact_lifetime_ms, archive_eligibility_age_ms,
                               minimum_online_record_retention_ms, minimum_archive_retention_ms,
                               effective_from, correlation_id, causation_id, created_at
                        FROM audit.audit_retention_policy_version
                        WHERE tenant_id = ? AND policy_version = ?
                        """,
                        (rs, rowNum) -> row(rs),
                        tenant.tenantId(),
                        version)
                .stream()
                .findFirst();
    }

    private static AuditRetentionPolicyVersion row(ResultSet rs) throws SQLException {
        return new AuditRetentionPolicyVersion(
                rs.getObject("id", UUID.class),
                rs.getLong("policy_version"),
                Duration.ofMillis(rs.getLong("export_artifact_lifetime_ms")),
                Duration.ofMillis(rs.getLong("archive_eligibility_age_ms")),
                Duration.ofMillis(rs.getLong("minimum_online_record_retention_ms")),
                Duration.ofMillis(rs.getLong("minimum_archive_retention_ms")),
                rs.getTimestamp("effective_from").toInstant(),
                rs.getObject("correlation_id", UUID.class),
                rs.getObject("causation_id", UUID.class),
                rs.getTimestamp("created_at").toInstant());
    }

    private static long millis(Duration value) {
        java.util.Objects.requireNonNull(value, "duration");
        long millis = value.toMillis();
        if (millis < 1L) {
            throw new IllegalArgumentException("retention duration must be at least one millisecond");
        }
        return millis;
    }
}
