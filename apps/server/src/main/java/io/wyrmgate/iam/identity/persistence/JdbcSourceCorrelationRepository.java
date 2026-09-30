package io.wyrmgate.iam.identity.persistence;

import io.wyrmgate.iam.identity.application.SourceCorrelationRepository;
import io.wyrmgate.iam.identity.application.SourceCorrelationRepository.LinkReplacement;
import io.wyrmgate.iam.identity.domain.IdentityLink;
import io.wyrmgate.iam.identity.domain.IdentityType;
import io.wyrmgate.iam.identity.domain.SourceAbsenceInference;
import io.wyrmgate.iam.identity.domain.SourceAbsencePolicyVersion;
import io.wyrmgate.iam.identity.domain.SourceAbsenceTrust;
import io.wyrmgate.iam.identity.domain.SourceCorrelationPolicyVersion;
import io.wyrmgate.iam.identity.domain.SourceImportCompleteness;
import io.wyrmgate.iam.identity.domain.SourceImportRun;
import io.wyrmgate.iam.identity.domain.SourceImportRunState;
import io.wyrmgate.iam.identity.domain.SourceLifecyclePolicyVersion;
import io.wyrmgate.iam.identity.domain.SourceRecord;
import io.wyrmgate.iam.identity.domain.SourceSystem;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
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
            SourceAbsenceTrust absenceTrust,
            String absenceTrustReason,
            String checkpointToken,
            String partialReason,
            Instant completedAt) {
        Objects.requireNonNull(absenceTrust, "absenceTrust");
        if (completeness == SourceImportCompleteness.PARTIAL
                && (partialReason == null || partialReason.isBlank())) {
            throw new IllegalArgumentException("partial import requires a reason");
        }
        if (absenceTrust == SourceAbsenceTrust.TRUSTED) {
            if (completeness != SourceImportCompleteness.COMPLETE) {
                throw new IllegalArgumentException("trusted absence requires COMPLETE import");
            }
            if (absenceTrustReason == null || absenceTrustReason.isBlank()) {
                throw new IllegalArgumentException("trusted absence requires a reason");
            }
            List<ImportAuthority> authority = jdbcTemplate.query(
                    """
                    SELECT source_system_id, started_at
                    FROM identity.source_import_run
                    WHERE tenant_id = ? AND id = ? AND run_state = 'RUNNING'
                    FOR UPDATE
                    """,
                    (rs, rowNum) -> new ImportAuthority(
                            rs.getObject("source_system_id", UUID.class),
                            rs.getTimestamp("started_at").toInstant()),
                    tenant.tenantId(),
                    runId);
            if (authority.isEmpty()) {
                throw new IllegalStateException("source import run is not active or does not exist");
            }
            ImportAuthority current = authority.getFirst();
            Long newer = jdbcTemplate.queryForObject(
                    """
                    SELECT count(*)
                    FROM identity.source_import_run
                    WHERE tenant_id = ?
                      AND source_system_id = ?
                      AND id <> ?
                      AND started_at > ?
                    """,
                    Long.class,
                    tenant.tenantId(),
                    current.sourceSystemId(),
                    runId,
                    Timestamp.from(current.startedAt()));
            if (newer != null && newer > 0) {
                throw new IllegalStateException(
                        "trusted absence requires the latest non-overlapped source import");
            }
        }

        int affected = jdbcTemplate.update(
                """
                UPDATE identity.source_import_run
                SET run_state = 'COMPLETED', completeness = ?, completed_at = ?,
                    absence_trust = ?, absence_trust_reason = ?,
                    checkpoint_token = ?, partial_reason = ?
                WHERE tenant_id = ? AND id = ? AND run_state = 'RUNNING'
                """,
                completeness.name(),
                Timestamp.from(completedAt),
                absenceTrust.name(),
                absenceTrustReason,
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
    public SourceAbsenceTrust findImportAbsenceTrust(TenantContext tenant, UUID runId) {
        return jdbcTemplate.query(
                """
                SELECT absence_trust
                FROM identity.source_import_run
                WHERE tenant_id = ? AND id = ?
                """,
                (rs, rowNum) -> SourceAbsenceTrust.valueOf(rs.getString("absence_trust")),
                tenant.tenantId(),
                runId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("source import run does not exist"));
    }

    @Override
    public boolean hasImportStartedAfter(
            TenantContext tenant, UUID sourceSystemId, Instant startedAt) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM identity.source_import_run
                WHERE tenant_id = ? AND source_system_id = ? AND started_at > ?
                """,
                Long.class,
                tenant.tenantId(),
                sourceSystemId,
                Timestamp.from(startedAt));
        return count != null && count > 0;
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
                INSERT INTO identity.source_record AS current_record (
                    id, tenant_id, source_system_id, native_key, observed_attributes,
                    source_updated_at, first_observed_at, last_observed_at,
                    last_import_run_id, last_complete_import_run_id)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, NULL)
                ON CONFLICT (tenant_id, source_system_id, native_key)
                DO UPDATE SET
                    observed_attributes = CASE
                        WHEN EXCLUDED.source_updated_at IS NOT NULL
                         AND current_record.source_updated_at IS NOT NULL
                         AND EXCLUDED.source_updated_at < current_record.source_updated_at
                            THEN current_record.observed_attributes
                        ELSE EXCLUDED.observed_attributes
                    END,
                    source_updated_at = CASE
                        WHEN EXCLUDED.source_updated_at IS NOT NULL
                         AND current_record.source_updated_at IS NOT NULL
                         AND EXCLUDED.source_updated_at < current_record.source_updated_at
                            THEN current_record.source_updated_at
                        ELSE EXCLUDED.source_updated_at
                    END,
                    last_observed_at = GREATEST(
                        current_record.last_observed_at,
                        EXCLUDED.last_observed_at),
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
    public Optional<SourceRecord> findSourceRecordForUpdate(
            TenantContext tenant, UUID sourceRecordId) {
        return querySourceRecord(
                "WHERE tenant_id = ? AND id = ? FOR UPDATE",
                tenant.tenantId(),
                sourceRecordId);
    }

    @Override
    public void lockTenantForCorrelation(TenantContext tenant) {
        List<UUID> rows = jdbcTemplate.query(
                "SELECT id FROM platform.tenant WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                tenant.tenantId());
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("tenant does not exist");
        }
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
            return new LinkReplacement(existing.get(), false, null);
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
        return new LinkReplacement(accepted, true, existing.map(IdentityLink::identityId).orElse(null));
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

    @Override
    public SourceCorrelationPolicyVersion replaceActiveCorrelationPolicy(
            TenantContext tenant,
            UUID sourceSystemId,
            UUID matchAttributeDefinitionVersionId,
            UUID matchMappingVersionId,
            boolean createIdentityOnNoMatch,
            IdentityType createdIdentityType,
            String displayNameSourcePath,
            Instant activatedAt,
            UUID newPolicyId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(sourceSystemId, "sourceSystemId");
        Objects.requireNonNull(matchAttributeDefinitionVersionId, "matchAttributeDefinitionVersionId");
        Objects.requireNonNull(matchMappingVersionId, "matchMappingVersionId");
        Objects.requireNonNull(activatedAt, "activatedAt");
        Objects.requireNonNull(newPolicyId, "newPolicyId");

        List<UUID> sourceRows = jdbcTemplate.query(
                "SELECT id FROM identity.source_system WHERE tenant_id = ? AND id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                tenant.tenantId(),
                sourceSystemId);
        if (sourceRows.isEmpty()) {
            throw new IllegalArgumentException("source system does not exist");
        }

        Long nextVersion = jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(max(version_number), 0) + 1
                FROM identity.source_correlation_policy_version
                WHERE tenant_id = ? AND source_system_id = ?
                """,
                Long.class,
                tenant.tenantId(),
                sourceSystemId);

        jdbcTemplate.update(
                """
                UPDATE identity.source_correlation_policy_version
                SET state = 'SUPERSEDED', superseded_at = ?
                WHERE tenant_id = ? AND source_system_id = ? AND state = 'ACTIVE'
                """,
                Timestamp.from(activatedAt),
                tenant.tenantId(),
                sourceSystemId);

        jdbcTemplate.update(
                """
                INSERT INTO identity.source_correlation_policy_version (
                    id, tenant_id, source_system_id,
                    match_attribute_definition_version_id, match_mapping_version_id,
                    version_number, create_identity_on_no_match,
                    created_identity_type, display_name_source_path,
                    state, created_at, activated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """,
                newPolicyId,
                tenant.tenantId(),
                sourceSystemId,
                matchAttributeDefinitionVersionId,
                matchMappingVersionId,
                nextVersion,
                createIdentityOnNoMatch,
                createdIdentityType == null ? null : createdIdentityType.name(),
                displayNameSourcePath,
                Timestamp.from(activatedAt),
                Timestamp.from(activatedAt));

        return findActiveCorrelationPolicy(tenant, sourceSystemId)
                .orElseThrow(() -> new IllegalStateException(
                        "activated source correlation policy could not be reloaded"));
    }

    @Override
    public Optional<SourceCorrelationPolicyVersion> findActiveCorrelationPolicy(
            TenantContext tenant, UUID sourceSystemId) {
        return jdbcTemplate.query(
                """
                SELECT id, source_system_id, match_attribute_definition_version_id,
                       match_mapping_version_id, version_number,
                       create_identity_on_no_match, created_identity_type,
                       display_name_source_path, state, created_at,
                       activated_at, superseded_at
                FROM identity.source_correlation_policy_version
                WHERE tenant_id = ? AND source_system_id = ? AND state = 'ACTIVE'
                """,
                (rs, rowNum) -> new SourceCorrelationPolicyVersion(
                        rs.getObject("id", UUID.class),
                        rs.getObject("source_system_id", UUID.class),
                        rs.getObject("match_attribute_definition_version_id", UUID.class),
                        rs.getObject("match_mapping_version_id", UUID.class),
                        rs.getLong("version_number"),
                        rs.getBoolean("create_identity_on_no_match"),
                        rs.getString("created_identity_type") == null
                                ? null
                                : IdentityType.valueOf(rs.getString("created_identity_type")),
                        rs.getString("display_name_source_path"),
                        SourceCorrelationPolicyVersion.State.valueOf(rs.getString("state")),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("activated_at").toInstant(),
                        instant(rs.getTimestamp("superseded_at"))),
                tenant.tenantId(),
                sourceSystemId)
                .stream()
                .findFirst();
    }

    @Override
    public SourceLifecyclePolicyVersion replaceActiveLifecyclePolicy(
            TenantContext tenant,
            UUID sourceSystemId,
            String sourcePath,
            List<SourceLifecyclePolicyVersion.Rule> rules,
            Instant activatedAt,
            UUID newPolicyId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(sourceSystemId, "sourceSystemId");
        Objects.requireNonNull(sourcePath, "sourcePath");
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(activatedAt, "activatedAt");
        Objects.requireNonNull(newPolicyId, "newPolicyId");

        List<UUID> sourceRows = jdbcTemplate.query(
                "SELECT id FROM identity.source_system WHERE tenant_id = ? AND id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                tenant.tenantId(),
                sourceSystemId);
        if (sourceRows.isEmpty()) {
            throw new IllegalArgumentException("source system does not exist");
        }

        Long nextVersion = jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(max(version_number), 0) + 1
                FROM identity.source_lifecycle_policy_version
                WHERE tenant_id = ? AND source_system_id = ?
                """,
                Long.class,
                tenant.tenantId(),
                sourceSystemId);

        jdbcTemplate.update(
                """
                UPDATE identity.source_lifecycle_policy_version
                SET state = 'SUPERSEDED', superseded_at = ?
                WHERE tenant_id = ? AND source_system_id = ? AND state = 'ACTIVE'
                """,
                Timestamp.from(activatedAt),
                tenant.tenantId(),
                sourceSystemId);

        jdbcTemplate.update(
                """
                INSERT INTO identity.source_lifecycle_policy_version (
                    id, tenant_id, source_system_id, source_path, version_number,
                    state, created_at, activated_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """,
                newPolicyId,
                tenant.tenantId(),
                sourceSystemId,
                sourcePath,
                nextVersion,
                Timestamp.from(activatedAt),
                Timestamp.from(activatedAt));

        for (SourceLifecyclePolicyVersion.Rule rule : rules) {
            jdbcTemplate.update(
                    """
                    INSERT INTO identity.source_lifecycle_policy_rule (
                        policy_version_id, tenant_id, source_value, target_lifecycle_state)
                    VALUES (?, ?, ?, ?)
                    """,
                    newPolicyId,
                    tenant.tenantId(),
                    rule.sourceValue(),
                    rule.targetState().name());
        }

        return findActiveLifecyclePolicy(tenant, sourceSystemId)
                .orElseThrow(() -> new IllegalStateException(
                        "activated source lifecycle policy could not be reloaded"));
    }

    @Override
    public Optional<SourceLifecyclePolicyVersion> findActiveLifecyclePolicy(
            TenantContext tenant, UUID sourceSystemId) {
        List<SourceLifecyclePolicyVersion> policies = jdbcTemplate.query(
                """
                SELECT id, source_system_id, source_path, version_number,
                       state, created_at, activated_at, superseded_at
                FROM identity.source_lifecycle_policy_version
                WHERE tenant_id = ? AND source_system_id = ? AND state = 'ACTIVE'
                """,
                (rs, rowNum) -> {
                    UUID policyId = rs.getObject("id", UUID.class);
                    List<SourceLifecyclePolicyVersion.Rule> rules = jdbcTemplate.query(
                            """
                            SELECT source_value, target_lifecycle_state
                            FROM identity.source_lifecycle_policy_rule
                            WHERE tenant_id = ? AND policy_version_id = ?
                            ORDER BY source_value
                            """,
                            (ruleRs, ruleRow) -> new SourceLifecyclePolicyVersion.Rule(
                                    ruleRs.getString("source_value"),
                                    io.wyrmgate.iam.identity.domain.IdentityLifecycleState.valueOf(
                                            ruleRs.getString("target_lifecycle_state"))),
                            tenant.tenantId(),
                            policyId);
                    return new SourceLifecyclePolicyVersion(
                            policyId,
                            rs.getObject("source_system_id", UUID.class),
                            rs.getString("source_path"),
                            rs.getLong("version_number"),
                            rules,
                            SourceLifecyclePolicyVersion.State.valueOf(rs.getString("state")),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("activated_at").toInstant(),
                            instant(rs.getTimestamp("superseded_at")));
                },
                tenant.tenantId(),
                sourceSystemId);
        return policies.stream().findFirst();
    }

    @Override
    public SourceAbsencePolicyVersion replaceActiveAbsencePolicy(
            TenantContext tenant,
            UUID sourceSystemId,
            int maxInferredTransitions,
            Instant activatedAt,
            UUID newPolicyId) {
        if (maxInferredTransitions < 1) {
            throw new IllegalArgumentException("maxInferredTransitions must be positive");
        }
        List<UUID> sourceRows = jdbcTemplate.query(
                "SELECT id FROM identity.source_system WHERE tenant_id = ? AND id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                tenant.tenantId(),
                sourceSystemId);
        if (sourceRows.isEmpty()) {
            throw new IllegalArgumentException("source system does not exist");
        }
        Long nextVersion = jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(max(version_number), 0) + 1
                FROM identity.source_absence_policy_version
                WHERE tenant_id = ? AND source_system_id = ?
                """,
                Long.class,
                tenant.tenantId(),
                sourceSystemId);
        jdbcTemplate.update(
                """
                UPDATE identity.source_absence_policy_version
                SET state = 'SUPERSEDED', superseded_at = ?
                WHERE tenant_id = ? AND source_system_id = ? AND state = 'ACTIVE'
                """,
                Timestamp.from(activatedAt),
                tenant.tenantId(),
                sourceSystemId);
        jdbcTemplate.update(
                """
                INSERT INTO identity.source_absence_policy_version (
                    id, tenant_id, source_system_id, version_number,
                    max_inferred_transitions, state, created_at, activated_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """,
                newPolicyId,
                tenant.tenantId(),
                sourceSystemId,
                nextVersion,
                maxInferredTransitions,
                Timestamp.from(activatedAt),
                Timestamp.from(activatedAt));
        return findActiveAbsencePolicy(tenant, sourceSystemId)
                .orElseThrow(() -> new IllegalStateException("absence policy could not be reloaded"));
    }

    @Override
    public Optional<SourceAbsencePolicyVersion> findActiveAbsencePolicy(
            TenantContext tenant, UUID sourceSystemId) {
        return jdbcTemplate.query(
                """
                SELECT id, source_system_id, version_number, max_inferred_transitions,
                       state, created_at, activated_at, superseded_at
                FROM identity.source_absence_policy_version
                WHERE tenant_id = ? AND source_system_id = ? AND state = 'ACTIVE'
                """,
                (rs, rowNum) -> new SourceAbsencePolicyVersion(
                        rs.getObject("id", UUID.class),
                        rs.getObject("source_system_id", UUID.class),
                        rs.getLong("version_number"),
                        rs.getInt("max_inferred_transitions"),
                        SourceAbsencePolicyVersion.State.valueOf(rs.getString("state")),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("activated_at").toInstant(),
                        instant(rs.getTimestamp("superseded_at"))),
                tenant.tenantId(),
                sourceSystemId)
                .stream()
                .findFirst();
    }

    @Override
    public SourceAbsenceInference startAbsenceInferenceIfAbsent(
            TenantContext tenant, SourceAbsenceInference candidate) {
        jdbcTemplate.update(
                """
                INSERT INTO identity.source_absence_inference (
                    id, tenant_id, source_system_id, import_run_id, policy_version_id,
                    max_inferred_transitions, process_state,
                    after_first_observed_at, after_source_record_id,
                    processed_candidate_count, inferred_transition_count,
                    revision, created_at, updated_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, import_run_id) DO NOTHING
                """,
                candidate.id(),
                tenant.tenantId(),
                candidate.sourceSystemId(),
                candidate.importRunId(),
                candidate.policyVersionId(),
                candidate.maxInferredTransitions(),
                candidate.state().name(),
                timestamp(candidate.afterFirstObservedAt()),
                candidate.afterSourceRecordId(),
                candidate.processedCandidateCount(),
                candidate.inferredTransitionCount(),
                candidate.revision(),
                Timestamp.from(candidate.createdAt()),
                Timestamp.from(candidate.updatedAt()),
                timestamp(candidate.completedAt()));
        return findAbsenceInferenceByRun(tenant, candidate.importRunId())
                .orElseThrow(() -> new IllegalStateException("absence inference could not be reloaded"));
    }

    @Override
    public Optional<SourceAbsenceInference> findAbsenceInferenceById(
            TenantContext tenant, UUID inferenceId) {
        return queryAbsenceInference(
                "WHERE tenant_id = ? AND id = ?",
                tenant.tenantId(),
                inferenceId);
    }

    private Optional<SourceAbsenceInference> findAbsenceInferenceByRun(
            TenantContext tenant, UUID importRunId) {
        return queryAbsenceInference(
                "WHERE tenant_id = ? AND import_run_id = ?",
                tenant.tenantId(),
                importRunId);
    }

    @Override
    public List<SourceRecord> findAbsentSourceRecordPage(
            TenantContext tenant,
            UUID sourceSystemId,
            UUID importRunId,
            Instant runStartedAt,
            Instant afterFirstObservedAt,
            UUID afterSourceRecordId,
            int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        String checkpoint = afterFirstObservedAt == null
                ? ""
                : " AND (first_observed_at, id) > (?, ?)";
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        args.add(tenant.tenantId());
        args.add(sourceSystemId);
        args.add(importRunId);
        args.add(Timestamp.from(runStartedAt));
        if (afterFirstObservedAt != null) {
            args.add(Timestamp.from(afterFirstObservedAt));
            args.add(afterSourceRecordId);
        }
        args.add(limit);
        return jdbcTemplate.query(
                """
                SELECT id, source_system_id, native_key,
                       observed_attributes::text AS observed_attributes,
                       source_updated_at, first_observed_at, last_observed_at,
                       last_import_run_id, last_complete_import_run_id
                FROM identity.source_record
                WHERE tenant_id = ?
                  AND source_system_id = ?
                  AND last_import_run_id <> ?
                  AND first_observed_at < ?
                """ + checkpoint + """
                ORDER BY first_observed_at, id
                LIMIT ?
                """,
                (rs, rowNum) -> sourceRecord(rs),
                args.toArray());
    }

    @Override
    public SourceAbsenceInference recordAbsenceInferenceProgress(
            TenantContext tenant,
            UUID inferenceId,
            Instant afterFirstObservedAt,
            UUID afterSourceRecordId,
            long processedDelta,
            long transitionDelta,
            SourceAbsenceInference.State state,
            long expectedRevision,
            Instant now) {
        if ((afterFirstObservedAt == null) != (afterSourceRecordId == null)) {
            throw new IllegalArgumentException("checkpoint fields must be both present or both absent");
        }
        if (processedDelta < 0 || transitionDelta < 0) {
            throw new IllegalArgumentException("progress deltas must not be negative");
        }
        int affected = jdbcTemplate.update(
                """
                UPDATE identity.source_absence_inference
                SET process_state = ?,
                    after_first_observed_at = ?,
                    after_source_record_id = ?,
                    processed_candidate_count = processed_candidate_count + ?,
                    inferred_transition_count = inferred_transition_count + ?,
                    revision = revision + 1,
                    updated_at = ?,
                    completed_at = ?
                WHERE tenant_id = ? AND id = ? AND process_state = 'RUNNING' AND revision = ?
                """,
                state.name(),
                timestamp(afterFirstObservedAt),
                afterSourceRecordId,
                processedDelta,
                transitionDelta,
                Timestamp.from(now),
                state == SourceAbsenceInference.State.RUNNING ? null : Timestamp.from(now),
                tenant.tenantId(),
                inferenceId,
                expectedRevision);
        if (affected != 1) {
            SourceAbsenceInference current = findAbsenceInferenceById(tenant, inferenceId)
                    .orElseThrow(() -> new IllegalArgumentException("absence inference does not exist"));
            if (current.revision() != expectedRevision) {
                throw new StaleWriteException("source-absence-inference", inferenceId, expectedRevision);
            }
            return current;
        }
        return findAbsenceInferenceById(tenant, inferenceId).orElseThrow();
    }

    private Optional<SourceAbsenceInference> queryAbsenceInference(
            String whereClause, Object... args) {
        return jdbcTemplate.query(
                """
                SELECT id, source_system_id, import_run_id, policy_version_id,
                       max_inferred_transitions, process_state,
                       after_first_observed_at, after_source_record_id,
                       processed_candidate_count, inferred_transition_count,
                       revision, created_at, updated_at, completed_at
                FROM identity.source_absence_inference
                """ + whereClause,
                (rs, rowNum) -> new SourceAbsenceInference(
                        rs.getObject("id", UUID.class),
                        rs.getObject("source_system_id", UUID.class),
                        rs.getObject("import_run_id", UUID.class),
                        rs.getObject("policy_version_id", UUID.class),
                        rs.getInt("max_inferred_transitions"),
                        SourceAbsenceInference.State.valueOf(rs.getString("process_state")),
                        instant(rs.getTimestamp("after_first_observed_at")),
                        rs.getObject("after_source_record_id", UUID.class),
                        rs.getLong("processed_candidate_count"),
                        rs.getLong("inferred_transition_count"),
                        rs.getLong("revision"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(),
                        instant(rs.getTimestamp("completed_at"))),
                args)
                .stream()
                .findFirst();
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
                (rs, rowNum) -> sourceRecord(rs),
                args);
        return rows.stream().findFirst();
    }

    private static SourceRecord sourceRecord(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SourceRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("source_system_id", UUID.class),
                rs.getString("native_key"),
                rs.getString("observed_attributes"),
                instant(rs.getTimestamp("source_updated_at")),
                rs.getTimestamp("first_observed_at").toInstant(),
                rs.getTimestamp("last_observed_at").toInstant(),
                rs.getObject("last_import_run_id", UUID.class),
                rs.getObject("last_complete_import_run_id", UUID.class));
    }

    private record ImportAuthority(UUID sourceSystemId, Instant startedAt) {
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
