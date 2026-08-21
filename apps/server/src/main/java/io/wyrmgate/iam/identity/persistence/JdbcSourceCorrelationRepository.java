package io.wyrmgate.iam.identity.persistence;

import io.wyrmgate.iam.identity.application.SourceCorrelationRepository;
import io.wyrmgate.iam.identity.application.SourceCorrelationRepository.LinkReplacement;
import io.wyrmgate.iam.identity.domain.IdentityLink;
import io.wyrmgate.iam.identity.domain.SourceImportCompleteness;
import io.wyrmgate.iam.identity.domain.SourceImportRun;
import io.wyrmgate.iam.identity.domain.SourceImportRunState;
import io.wyrmgate.iam.identity.domain.SourceRecord;
import io.wyrmgate.iam.identity.domain.SourceSystem;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC adapter for Identity-owned source observations, import provenance and correlation history. */
public final class JdbcSourceCorrelationRepository implements SourceCorrelationRepository {

    private final JdbcTemplate jdbcTemplate;
    private final IdGenerator idGenerator;

    public JdbcSourceCorrelationRepository(JdbcTemplate jdbcTemplate, IdGenerator idGenerator) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    }

    @Override
    public void insertSourceSystem(TenantContext tenant, SourceSystem sourceSystem) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(sourceSystem, "sourceSystem");
        jdbcTemplate.update(
                """
                INSERT INTO identity.source_system (
                    id, tenant_id, code, name, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                sourceSystem.id(),
                tenant.tenantId(),
                sourceSystem.code(),
                sourceSystem.name(),
                sourceSystem.revision(),
                Timestamp.from(sourceSystem.createdAt()),
                Timestamp.from(sourceSystem.updatedAt()));
    }

    @Override
    public Optional<SourceSystem> findSourceSystem(TenantContext tenant, UUID sourceSystemId) {
        List<SourceSystem> rows = jdbcTemplate.query(
                """
                SELECT id, code, name, revision, created_at, updated_at
                FROM identity.source_system
                WHERE tenant_id = ? AND id = ?
                """,
                (rs, rowNum) -> new SourceSystem(
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getLong("revision"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                tenant.tenantId(),
                sourceSystemId);
        return rows.stream().findFirst();
    }

    @Override
    public void insertImportRun(TenantContext tenant, SourceImportRun run) {
        jdbcTemplate.update(
                """
                INSERT INTO identity.source_import_run (
                    id, tenant_id, source_system_id, run_state, completeness,
                    started_at, completed_at, checkpoint_token, partial_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                run.id(),
                tenant.tenantId(),
                run.sourceSystemId(),
                run.state().name(),
                run.completeness().name(),
                Timestamp.from(run.startedAt()),
                timestamp(run.completedAt()),
                run.checkpointToken(),
                run.partialReason());
    }

    @Override
    public SourceImportRun completeImportRun(
            TenantContext tenant,
            UUID runId,
            SourceImportCompleteness completeness,
            String checkpointToken,
            String partialReason,
            Instant completedAt) {
        if (completeness == SourceImportCompleteness.PARTIAL
                && (partialReason == null || partialReason.isBlank())) {
            throw new IllegalArgumentException("partial import requires a reason");
        }
        int affected = jdbcTemplate.update(
                """
                UPDATE identity.source_import_run
                SET run_state = 'COMPLETED', completeness = ?, completed_at = ?,
                    checkpoint_token = ?, partial_reason = ?
                WHERE tenant_id = ? AND id = ? AND run_state = 'RUNNING'
                """,
                completeness.name(),
                Timestamp.from(completedAt),
                checkpointToken,
                partialReason,
                tenant.tenantId(),
                runId);
        if (affected != 1) {
            throw new IllegalStateException("source import run is not active or does not exist");
        }
        if (completeness == SourceImportCompleteness.COMPLETE) {
            jdbcTemplate.update(
                    """
                    UPDATE identity.source_record
                    SET last_complete_import_run_id = ?
                    WHERE tenant_id = ? AND last_import_run_id = ?
                    """,
                    runId,
                    tenant.tenantId(),
                    runId);
        }
        return findImportRun(tenant, runId)
                .orElseThrow(() -> new IllegalStateException("completed source import run could not be reloaded"));
    }

    @Override
    public Optional<SourceImportRun> findImportRun(TenantContext tenant, UUID runId) {
        List<SourceImportRun> rows = jdbcTemplate.query(
                """
                SELECT id, source_system_id, run_state, completeness, started_at,
                       completed_at, checkpoint_token, partial_reason
                FROM identity.source_import_run
                WHERE tenant_id = ? AND id = ?
                """,
                (rs, rowNum) -> new SourceImportRun(
                        rs.getObject("id", UUID.class),
                        rs.getObject("source_system_id", UUID.class),
                        SourceImportRunState.valueOf(rs.getString("run_state")),
                        SourceImportCompleteness.valueOf(rs.getString("completeness")),
                        rs.getTimestamp("started_at").toInstant(),
                        instant(rs.getTimestamp("completed_at")),
                        rs.getString("checkpoint_token"),
                        rs.getString("partial_reason")),
                tenant.tenantId(),
                runId);
        return rows.stream().findFirst();
    }

    @Override
    public SourceRecord upsertPositiveObservation(
            TenantContext tenant,
            UUID sourceSystemId,
            UUID importRunId,
            String nativeKey,
            String observedAttributesJson,
            Instant sourceUpdatedAt,
            Instant observedAt) {
        requireText(nativeKey, "nativeKey");
        requireText(observedAttributesJson, "observedAttributesJson");
        Objects.requireNonNull(observedAt, "observedAt");

        List<UUID> activeRunSources = jdbcTemplate.query(
                """
                SELECT source_system_id
                FROM identity.source_import_run
                WHERE tenant_id = ? AND id = ? AND run_state = 'RUNNING'
                FOR UPDATE
                """,
                (rs, rowNum) -> rs.getObject("source_system_id", UUID.class),
                tenant.tenantId(),
                importRunId);
        if (activeRunSources.isEmpty()) {
            throw new IllegalStateException("source observations require a running import");
        }
        if (!activeRunSources.getFirst().equals(sourceSystemId)) {
            throw new IllegalArgumentException("source import run belongs to a different source system");
        }

        UUID proposedId = idGenerator.nextId();
        jdbcTemplate.update(
                """
                INSERT INTO identity.source_record (
                    id, tenant_id, source_system_id, native_key, observed_attributes,
                    source_updated_at, first_observed_at, last_observed_at,
                    last_import_run_id, last_complete_import_run_id)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, NULL)
                ON CONFLICT (tenant_id, source_system_id, native_key)
                DO UPDATE SET
                    observed_attributes = EXCLUDED.observed_attributes,
                    source_updated_at = EXCLUDED.source_updated_at,
                    last_observed_at = EXCLUDED.last_observed_at,
                    last_import_run_id = EXCLUDED.last_import_run_id
                """,
                proposedId,
                tenant.tenantId(),
                sourceSystemId,
                nativeKey,
                observedAttributesJson,
                timestamp(sourceUpdatedAt),
                Timestamp.from(observedAt),
                Timestamp.from(observedAt),
                importRunId);
        return findSourceRecordByNativeKey(tenant, sourceSystemId, nativeKey)
                .orElseThrow(() -> new IllegalStateException("source record could not be reloaded"));
    }

    @Override
    public Optional<SourceRecord> findSourceRecord(TenantContext tenant, UUID sourceRecordId) {
        return querySourceRecord(
                "WHERE tenant_id = ? AND id = ?",
                tenant.tenantId(),
                sourceRecordId);
    }

    @Override
    public Optional<SourceRecord> findSourceRecordByNativeKey(
            TenantContext tenant,
            UUID sourceSystemId,
            String nativeKey) {
        return querySourceRecord(
                "WHERE tenant_id = ? AND source_system_id = ? AND native_key = ?",
                tenant.tenantId(),
                sourceSystemId,
                nativeKey);
    }

    @Override
    public LinkReplacement replaceAcceptedLink(
            TenantContext tenant,
            UUID sourceRecordId,
            UUID identityId,
            String correlationReason,
            Instant linkedAt,
            UUID correlationId,
            UUID causationId,
            UUID newLinkId) {
        requireText(correlationReason, "correlationReason");
        List<UUID> lockedRecords = jdbcTemplate.query(
                "SELECT id FROM identity.source_record WHERE tenant_id = ? AND id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                tenant.tenantId(),
                sourceRecordId);
        if (lockedRecords.isEmpty()) {
            throw new IllegalArgumentException("source record does not exist");
        }

        Optional<IdentityLink> existing = findActiveAcceptedLink(tenant, sourceRecordId);
        if (existing.isPresent() && existing.get().identityId().equals(identityId)) {
            return new LinkReplacement(existing.get(), false);
        }
        existing.ifPresent(link -> jdbcTemplate.update(
                """
                UPDATE identity.identity_link
                SET link_state = 'SUPERSEDED', ended_at = ?
                WHERE tenant_id = ? AND id = ? AND link_state = 'ACCEPTED' AND ended_at IS NULL
                """,
                Timestamp.from(linkedAt),
                tenant.tenantId(),
                link.id()));

        jdbcTemplate.update(
                """
                INSERT INTO identity.identity_link (
                    id, tenant_id, source_record_id, identity_id, link_state,
                    linked_at, ended_at, correlation_reason, correlation_id, causation_id)
                VALUES (?, ?, ?, ?, 'ACCEPTED', ?, NULL, ?, ?, ?)
                """,
                newLinkId,
                tenant.tenantId(),
                sourceRecordId,
                identityId,
                Timestamp.from(linkedAt),
                correlationReason,
                correlationId,
                causationId);
        IdentityLink accepted = findActiveAcceptedLink(tenant, sourceRecordId)
                .orElseThrow(() -> new IllegalStateException("accepted identity link could not be reloaded"));
        return new LinkReplacement(accepted, true);
    }

    @Override
    public Optional<IdentityLink> findActiveAcceptedLink(TenantContext tenant, UUID sourceRecordId) {
        List<IdentityLink> rows = jdbcTemplate.query(
                """
                SELECT id, source_record_id, identity_id, link_state, linked_at, ended_at,
                       correlation_reason, correlation_id, causation_id
                FROM identity.identity_link
                WHERE tenant_id = ? AND source_record_id = ?
                  AND link_state = 'ACCEPTED' AND ended_at IS NULL
                """,
                (rs, rowNum) -> new IdentityLink(
                        rs.getObject("id", UUID.class),
                        rs.getObject("source_record_id", UUID.class),
                        rs.getObject("identity_id", UUID.class),
                        IdentityLink.LinkState.valueOf(rs.getString("link_state")),
                        rs.getTimestamp("linked_at").toInstant(),
                        instant(rs.getTimestamp("ended_at")),
                        rs.getString("correlation_reason"),
                        rs.getObject("correlation_id", UUID.class),
                        rs.getObject("causation_id", UUID.class)),
                tenant.tenantId(),
                sourceRecordId);
        return rows.stream().findFirst();
    }

    private Optional<SourceRecord> querySourceRecord(String whereClause, Object... args) {
        List<SourceRecord> rows = jdbcTemplate.query(
                """
                SELECT id, source_system_id, native_key,
                       observed_attributes::text AS observed_attributes,
                       source_updated_at, first_observed_at, last_observed_at,
                       last_import_run_id, last_complete_import_run_id
                FROM identity.source_record
                """ + whereClause,
                (rs, rowNum) -> new SourceRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("source_system_id", UUID.class),
                        rs.getString("native_key"),
                        rs.getString("observed_attributes"),
                        instant(rs.getTimestamp("source_updated_at")),
                        rs.getTimestamp("first_observed_at").toInstant(),
                        rs.getTimestamp("last_observed_at").toInstant(),
                        rs.getObject("last_import_run_id", UUID.class),
                        rs.getObject("last_complete_import_run_id", UUID.class)),
                args);
        return rows.stream().findFirst();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
