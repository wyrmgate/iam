package io.wyrmgate.iam.governance.persistence;

import io.wyrmgate.iam.governance.application.GovernanceFindingRepository;
import io.wyrmgate.iam.governance.application.GovernanceFindingRepository.GovernanceFinding;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcGovernanceFindingRepository implements GovernanceFindingRepository {

    private final JdbcTemplate jdbc;

    public JdbcGovernanceFindingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<GovernanceFinding> findByKey(TenantContext tenant, String findingKey) {
        return jdbc.query("""
                SELECT id, finding_key, finding_type, subject_kind,
                       connector_binding_id, provider_stable_id,
                       related_provider_stable_id, lifecycle_state,
                       first_observed_at, last_observed_at, resolved_at,
                       revision, created_at, updated_at
                FROM governance.finding
                WHERE tenant_id = ? AND finding_key = ?
                """, (rs,row) -> row(rs), tenant.tenantId(), findingKey)
                .stream().findFirst();
    }

    @Override
    public List<GovernanceFinding> findOpenByBinding(
            TenantContext tenant, UUID connectorBindingId) {
        return jdbc.query("""
                SELECT id, finding_key, finding_type, subject_kind,
                       connector_binding_id, provider_stable_id,
                       related_provider_stable_id, lifecycle_state,
                       first_observed_at, last_observed_at, resolved_at,
                       revision, created_at, updated_at
                FROM governance.finding
                WHERE tenant_id = ? AND connector_binding_id = ?
                  AND lifecycle_state = 'OPEN'
                ORDER BY finding_key
                """, (rs,row) -> row(rs), tenant.tenantId(), connectorBindingId);
    }

    @Override
    public GovernanceFinding insert(
            TenantContext tenant,
            UUID id,
            String findingKey,
            String findingType,
            String subjectKind,
            UUID connectorBindingId,
            String providerStableId,
            String relatedProviderStableId,
            Instant observedAt) {
        jdbc.update("""
                INSERT INTO governance.finding (
                    id, tenant_id, finding_key, finding_type, subject_kind,
                    connector_binding_id, provider_stable_id,
                    related_provider_stable_id, lifecycle_state,
                    first_observed_at, last_observed_at, resolved_at,
                    revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, NULL, 1, ?, ?)
                """,
                id, tenant.tenantId(), findingKey, findingType, subjectKind,
                connectorBindingId, providerStableId, relatedProviderStableId,
                Timestamp.from(observedAt), Timestamp.from(observedAt),
                Timestamp.from(observedAt), Timestamp.from(observedAt));
        return findByKey(tenant, findingKey).orElseThrow();
    }

    @Override
    public GovernanceFinding touchOpen(
            TenantContext tenant, UUID id, long expectedRevision, Instant observedAt) {
        int affected = jdbc.update("""
                UPDATE governance.finding
                SET last_observed_at = GREATEST(last_observed_at, ?),
                    revision = revision + 1,
                    updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ?
                  AND lifecycle_state = 'OPEN'
                """,
                Timestamp.from(observedAt), Timestamp.from(observedAt),
                tenant.tenantId(), id, expectedRevision);
        requireUpdated(tenant, id, expectedRevision, affected);
        return findById(tenant, id);
    }

    @Override
    public GovernanceFinding reopen(
            TenantContext tenant, UUID id, long expectedRevision, Instant observedAt) {
        int affected = jdbc.update("""
                UPDATE governance.finding
                SET lifecycle_state = 'OPEN',
                    last_observed_at = GREATEST(last_observed_at, ?),
                    resolved_at = NULL,
                    revision = revision + 1,
                    updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ?
                  AND lifecycle_state = 'RESOLVED'
                """,
                Timestamp.from(observedAt), Timestamp.from(observedAt),
                tenant.tenantId(), id, expectedRevision);
        requireUpdated(tenant, id, expectedRevision, affected);
        return findById(tenant, id);
    }

    @Override
    public GovernanceFinding resolve(
            TenantContext tenant, UUID id, long expectedRevision, Instant resolvedAt) {
        int affected = jdbc.update("""
                UPDATE governance.finding
                SET lifecycle_state = 'RESOLVED',
                    resolved_at = ?,
                    revision = revision + 1,
                    updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ?
                  AND lifecycle_state = 'OPEN'
                """,
                Timestamp.from(resolvedAt), Timestamp.from(resolvedAt),
                tenant.tenantId(), id, expectedRevision);
        requireUpdated(tenant, id, expectedRevision, affected);
        return findById(tenant, id);
    }

    private GovernanceFinding findById(TenantContext tenant, UUID id) {
        return jdbc.query("""
                SELECT id, finding_key, finding_type, subject_kind,
                       connector_binding_id, provider_stable_id,
                       related_provider_stable_id, lifecycle_state,
                       first_observed_at, last_observed_at, resolved_at,
                       revision, created_at, updated_at
                FROM governance.finding
                WHERE tenant_id = ? AND id = ?
                """, (rs,row) -> row(rs), tenant.tenantId(), id)
                .stream().findFirst().orElseThrow();
    }

    private void requireUpdated(
            TenantContext tenant, UUID id, long expectedRevision, int affected) {
        if (affected == 1) return;
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM governance.finding WHERE tenant_id = ? AND id = ?",
                Integer.class, tenant.tenantId(), id);
        if (count == null || count == 0) {
            throw new IllegalArgumentException("governance finding does not exist");
        }
        throw new StaleWriteException("governance-finding", id, expectedRevision);
    }

    private static GovernanceFinding row(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        Timestamp resolved = rs.getTimestamp("resolved_at");
        return new GovernanceFinding(
                rs.getObject("id", UUID.class),
                rs.getString("finding_key"),
                rs.getString("finding_type"),
                rs.getString("subject_kind"),
                rs.getObject("connector_binding_id", UUID.class),
                rs.getString("provider_stable_id"),
                rs.getString("related_provider_stable_id"),
                rs.getString("lifecycle_state"),
                rs.getTimestamp("first_observed_at").toInstant(),
                rs.getTimestamp("last_observed_at").toInstant(),
                resolved == null ? null : resolved.toInstant(),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
