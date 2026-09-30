package io.wyrmgate.iam.identity.persistence;

import io.wyrmgate.iam.identity.application.IdentityMergeSplitRepository;
import io.wyrmgate.iam.identity.application.IdentityRepository;
import io.wyrmgate.iam.identity.domain.Identity;
import io.wyrmgate.iam.identity.domain.IdentityLink;
import io.wyrmgate.iam.identity.domain.IdentityMergeOperation;
import io.wyrmgate.iam.identity.domain.IdentitySplitOperation;
import io.wyrmgate.iam.identity.domain.Principal;
import io.wyrmgate.iam.identity.domain.PrincipalKind;
import io.wyrmgate.iam.identity.domain.PrincipalLifecycleState;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC adapter for immutable merge/split evidence and merge/split-only relationship reassignment. */
public final class JdbcIdentityMergeSplitRepository implements IdentityMergeSplitRepository {

    private final JdbcTemplate jdbc;
    private final IdentityRepository identities;

    public JdbcIdentityMergeSplitRepository(
            JdbcTemplate jdbc,
            IdentityRepository identities) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
        this.identities = java.util.Objects.requireNonNull(identities, "identities");
    }

    @Override
    public List<Identity> lockIdentities(
            TenantContext tenant,
            List<UUID> identityIds) {
        List<UUID> ordered = identityIds.stream()
                .distinct()
                .sorted(Comparator.comparing(UUID::toString))
                .toList();
        List<Identity> result = new ArrayList<>();
        for (UUID identityId : ordered) {
            List<UUID> locked = jdbc.query(
                    """
                    SELECT id FROM identity.identity
                    WHERE tenant_id = ? AND id = ?
                    FOR UPDATE
                    """,
                    (rs, rowNum) -> rs.getObject("id", UUID.class),
                    tenant.tenantId(),
                    identityId);
            if (locked.isEmpty()) {
                throw new IllegalArgumentException("Identity does not exist");
            }
            result.add(identities.findById(tenant, identityId).orElseThrow());
        }
        return List.copyOf(result);
    }

    @Override
    public List<IdentityLink> findActiveLinksByIdentity(
            TenantContext tenant,
            UUID identityId) {
        return jdbc.query(
                """
                SELECT id, source_record_id, identity_id, link_state,
                       linked_at, ended_at, correlation_reason,
                       correlation_id, causation_id
                FROM identity.identity_link
                WHERE tenant_id = ? AND identity_id = ?
                  AND link_state = 'ACCEPTED' AND ended_at IS NULL
                ORDER BY source_record_id, id
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
                identityId);
    }

    @Override
    public List<Principal> findPrincipalsByIdentity(
            TenantContext tenant,
            UUID identityId) {
        return jdbc.query(
                """
                SELECT id, identity_id, application_target_id, principal_kind,
                       native_principal_key, lifecycle_state, revision,
                       created_at, updated_at
                FROM identity.principal
                WHERE tenant_id = ? AND identity_id = ?
                ORDER BY id
                """,
                (rs, rowNum) -> principal(rs),
                tenant.tenantId(),
                identityId);
    }

    @Override
    public Principal reassignPrincipal(
            TenantContext tenant,
            UUID principalId,
            UUID fromIdentityId,
            UUID toIdentityId,
            long expectedRevision,
            Instant now) {
        int affected = jdbc.update(
                """
                UPDATE identity.principal
                SET identity_id = ?, revision = revision + 1, updated_at = ?
                WHERE tenant_id = ? AND id = ? AND identity_id = ? AND revision = ?
                """,
                toIdentityId,
                Timestamp.from(now),
                tenant.tenantId(),
                principalId,
                fromIdentityId,
                expectedRevision);
        if (affected != 1) {
            Principal current = jdbc.query(
                    """
                    SELECT id, identity_id, application_target_id, principal_kind,
                           native_principal_key, lifecycle_state, revision,
                           created_at, updated_at
                    FROM identity.principal
                    WHERE tenant_id = ? AND id = ?
                    """,
                    (rs, rowNum) -> principal(rs),
                    tenant.tenantId(),
                    principalId)
                    .stream()
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Principal does not exist"));
            if (current.revision() != expectedRevision) {
                throw new StaleWriteException("principal", principalId, expectedRevision);
            }
            throw new IllegalArgumentException("Principal is no longer owned by the expected Identity");
        }
        return jdbc.query(
                """
                SELECT id, identity_id, application_target_id, principal_kind,
                       native_principal_key, lifecycle_state, revision,
                       created_at, updated_at
                FROM identity.principal
                WHERE tenant_id = ? AND id = ?
                """,
                (rs, rowNum) -> principal(rs),
                tenant.tenantId(),
                principalId)
                .stream()
                .findFirst()
                .orElseThrow();
    }

    @Override
    public void insertMergeOperation(
            TenantContext tenant,
            IdentityMergeOperation operation) {
        jdbc.update(
                """
                INSERT INTO identity.identity_merge_operation (
                    id, tenant_id, survivor_identity_id, absorbed_identity_id,
                    survivor_revision_before, absorbed_revision_before,
                    moved_link_count, moved_principal_count, reason,
                    correlation_id, causation_id, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                operation.id(),
                tenant.tenantId(),
                operation.survivorIdentityId(),
                operation.absorbedIdentityId(),
                operation.survivorRevisionBefore(),
                operation.absorbedRevisionBefore(),
                operation.movedLinkCount(),
                operation.movedPrincipalCount(),
                operation.reason(),
                operation.correlationId(),
                operation.causationId(),
                Timestamp.from(operation.completedAt()));
    }

    @Override
    public void insertSplitOperation(
            TenantContext tenant,
            IdentitySplitOperation operation) {
        jdbc.update(
                """
                INSERT INTO identity.identity_split_operation (
                    id, tenant_id, source_identity_id, new_identity_id,
                    source_revision_before, reason, correlation_id,
                    causation_id, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                operation.id(),
                tenant.tenantId(),
                operation.sourceIdentityId(),
                operation.newIdentityId(),
                operation.sourceRevisionBefore(),
                operation.reason(),
                operation.correlationId(),
                operation.causationId(),
                Timestamp.from(operation.completedAt()));
        for (UUID sourceRecordId : operation.movedSourceRecordIds()) {
            jdbc.update(
                    """
                    INSERT INTO identity.identity_split_source_record (
                        tenant_id, operation_id, source_record_id)
                    VALUES (?, ?, ?)
                    """,
                    tenant.tenantId(),
                    operation.id(),
                    sourceRecordId);
        }
        for (UUID principalId : operation.movedPrincipalIds()) {
            jdbc.update(
                    """
                    INSERT INTO identity.identity_split_principal (
                        tenant_id, operation_id, principal_id)
                    VALUES (?, ?, ?)
                    """,
                    tenant.tenantId(),
                    operation.id(),
                    principalId);
        }
    }

    @Override
    public Optional<IdentityMergeOperation> findMergeOperation(
            TenantContext tenant,
            UUID operationId) {
        return jdbc.query(
                """
                SELECT id, survivor_identity_id, absorbed_identity_id,
                       survivor_revision_before, absorbed_revision_before,
                       moved_link_count, moved_principal_count, reason,
                       correlation_id, causation_id, completed_at
                FROM identity.identity_merge_operation
                WHERE tenant_id = ? AND id = ?
                """,
                (rs, rowNum) -> new IdentityMergeOperation(
                        rs.getObject("id", UUID.class),
                        rs.getObject("survivor_identity_id", UUID.class),
                        rs.getObject("absorbed_identity_id", UUID.class),
                        rs.getLong("survivor_revision_before"),
                        rs.getLong("absorbed_revision_before"),
                        rs.getInt("moved_link_count"),
                        rs.getInt("moved_principal_count"),
                        rs.getString("reason"),
                        rs.getObject("correlation_id", UUID.class),
                        rs.getObject("causation_id", UUID.class),
                        rs.getTimestamp("completed_at").toInstant()),
                tenant.tenantId(),
                operationId)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<IdentitySplitOperation> findSplitOperation(
            TenantContext tenant,
            UUID operationId) {
        return jdbc.query(
                """
                SELECT id, source_identity_id, new_identity_id,
                       source_revision_before, reason, correlation_id,
                       causation_id, completed_at
                FROM identity.identity_split_operation
                WHERE tenant_id = ? AND id = ?
                """,
                (rs, rowNum) -> new IdentitySplitOperation(
                        rs.getObject("id", UUID.class),
                        rs.getObject("source_identity_id", UUID.class),
                        rs.getObject("new_identity_id", UUID.class),
                        rs.getLong("source_revision_before"),
                        splitSourceRecords(tenant, operationId),
                        splitPrincipals(tenant, operationId),
                        rs.getString("reason"),
                        rs.getObject("correlation_id", UUID.class),
                        rs.getObject("causation_id", UUID.class),
                        rs.getTimestamp("completed_at").toInstant()),
                tenant.tenantId(),
                operationId)
                .stream()
                .findFirst();
    }

    @Override
    public boolean hasCompletedMergeForAbsorbedIdentity(
            TenantContext tenant,
            UUID absorbedIdentityId) {
        Long count = jdbc.queryForObject(
                """
                SELECT count(*) FROM identity.identity_merge_operation
                WHERE tenant_id = ? AND absorbed_identity_id = ?
                """,
                Long.class,
                tenant.tenantId(),
                absorbedIdentityId);
        return count != null && count > 0;
    }

    private List<UUID> splitSourceRecords(
            TenantContext tenant,
            UUID operationId) {
        return jdbc.query(
                """
                SELECT source_record_id
                FROM identity.identity_split_source_record
                WHERE tenant_id = ? AND operation_id = ?
                ORDER BY source_record_id
                """,
                (rs, rowNum) -> rs.getObject("source_record_id", UUID.class),
                tenant.tenantId(),
                operationId);
    }

    private List<UUID> splitPrincipals(
            TenantContext tenant,
            UUID operationId) {
        return jdbc.query(
                """
                SELECT principal_id
                FROM identity.identity_split_principal
                WHERE tenant_id = ? AND operation_id = ?
                ORDER BY principal_id
                """,
                (rs, rowNum) -> rs.getObject("principal_id", UUID.class),
                tenant.tenantId(),
                operationId);
    }

    private static Principal principal(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new Principal(
                rs.getObject("id", UUID.class),
                rs.getObject("identity_id", UUID.class),
                rs.getObject("application_target_id", UUID.class),
                PrincipalKind.valueOf(rs.getString("principal_kind")),
                rs.getString("native_principal_key"),
                PrincipalLifecycleState.valueOf(rs.getString("lifecycle_state")),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
