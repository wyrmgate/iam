package io.wyrmgate.iam.credential.persistence;

import io.wyrmgate.iam.credential.application.CredentialQueryModels.CredentialPosition;
import io.wyrmgate.iam.credential.application.CredentialQueryModels.RotationPosition;
import io.wyrmgate.iam.credential.application.CredentialRepository;
import io.wyrmgate.iam.credential.domain.CredentialModels.*;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcCredentialRepository
        implements CredentialRepository {

    private final JdbcTemplate jdbc;

    public JdbcCredentialRepository(
            JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertCredential(
            TenantContext tenant,
            Credential credential) {
        jdbc.update("""
                INSERT INTO credential.credential (
                    id, tenant_id, principal_id,
                    credential_kind, secret_provider_type,
                    secret_reference_key, lifecycle_state,
                    valid_from, valid_until, revision,
                    created_at, updated_at,
                    compromised_at, revoked_at, expired_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                credential.id(),
                tenant.tenantId(),
                credential.principalId(),
                credential.kind().name(),
                credential.secretReference().providerType(),
                credential.secretReference().referenceKey(),
                credential.state().name(),
                timestamp(credential.validFrom()),
                timestamp(credential.validUntil()),
                credential.revision(),
                Timestamp.from(credential.createdAt()),
                Timestamp.from(credential.updatedAt()),
                timestamp(credential.compromisedAt()),
                timestamp(credential.revokedAt()),
                timestamp(credential.expiredAt()));
    }

    @Override
    public Optional<Credential> findCredential(
            TenantContext tenant,
            UUID credentialId) {
        return jdbc.query("""
                SELECT id, principal_id, credential_kind,
                       secret_provider_type, secret_reference_key,
                       lifecycle_state, valid_from, valid_until,
                       revision, created_at, updated_at,
                       compromised_at, revoked_at, expired_at
                FROM credential.credential
                WHERE tenant_id = ? AND id = ?
                """,
                (rs,row) -> credential(rs),
                tenant.tenantId(),
                credentialId)
                .stream()
                .findFirst();
    }

    @Override
    public List<Credential> listCredentials(
            TenantContext tenant,
            UUID principalId,
            CredentialPosition after,
            int limit) {
        if (after == null) {
            return jdbc.query("""
                    SELECT id, principal_id, credential_kind,
                           secret_provider_type, secret_reference_key,
                           lifecycle_state, valid_from, valid_until,
                           revision, created_at, updated_at,
                           compromised_at, revoked_at, expired_at
                    FROM credential.credential
                    WHERE tenant_id = ?
                      AND principal_id = ?
                    ORDER BY created_at ASC, id ASC
                    LIMIT ?
                    """,
                    (rs,row) -> credential(rs),
                    tenant.tenantId(),
                    principalId,
                    limit);
        }
        return jdbc.query("""
                SELECT id, principal_id, credential_kind,
                       secret_provider_type, secret_reference_key,
                       lifecycle_state, valid_from, valid_until,
                       revision, created_at, updated_at,
                       compromised_at, revoked_at, expired_at
                FROM credential.credential
                WHERE tenant_id = ?
                  AND principal_id = ?
                  AND (
                      created_at > ?
                      OR (created_at = ? AND id > ?)
                  )
                ORDER BY created_at ASC, id ASC
                LIMIT ?
                """,
                (rs,row) -> credential(rs),
                tenant.tenantId(),
                principalId,
                Timestamp.from(after.createdAt()),
                Timestamp.from(after.createdAt()),
                after.id(),
                limit);
    }

    @Override
    public List<Credential> findEffectiveCredentials(
            TenantContext tenant,
            UUID principalId,
            CredentialKind kind,
            Instant at) {
        return jdbc.query("""
                SELECT id, principal_id, credential_kind,
                       secret_provider_type, secret_reference_key,
                       lifecycle_state, valid_from, valid_until,
                       revision, created_at, updated_at,
                       compromised_at, revoked_at, expired_at
                FROM credential.credential
                WHERE tenant_id = ?
                  AND principal_id = ?
                  AND credential_kind = ?
                  AND lifecycle_state = 'ACTIVE'
                  AND (valid_from IS NULL OR valid_from <= ?)
                  AND (valid_until IS NULL OR valid_until > ?)
                ORDER BY created_at DESC, id DESC
                """,
                (rs,row) -> credential(rs),
                tenant.tenantId(),
                principalId,
                kind.name(),
                Timestamp.from(at),
                Timestamp.from(at));
    }

    @Override
    public Credential updateCredentialState(
            TenantContext tenant,
            UUID credentialId,
            CredentialState state,
            long expectedRevision,
            Instant now,
            Instant compromisedAt,
            Instant revokedAt,
            Instant expiredAt) {
        int affected = jdbc.update("""
                UPDATE credential.credential
                SET lifecycle_state = ?,
                    revision = revision + 1,
                    updated_at = ?,
                    compromised_at = ?,
                    revoked_at = ?,
                    expired_at = ?
                WHERE tenant_id = ?
                  AND id = ?
                  AND revision = ?
                """,
                state.name(),
                Timestamp.from(now),
                timestamp(compromisedAt),
                timestamp(revokedAt),
                timestamp(expiredAt),
                tenant.tenantId(),
                credentialId,
                expectedRevision);
        if (affected != 1) {
            Credential current = findCredential(
                            tenant, credentialId)
                    .orElseThrow(() ->
                            new IllegalArgumentException(
                                    "Credential does not exist"));
            if (current.revision()
                    != expectedRevision) {
                throw new StaleWriteException(
                        "credential",
                        credentialId,
                        expectedRevision);
            }
            throw new IllegalStateException(
                    "Credential state did not update");
        }
        return findCredential(
                tenant, credentialId).orElseThrow();
    }

    @Override
    public void insertRotation(
            TenantContext tenant,
            CredentialRotation rotation) {
        jdbc.update("""
                INSERT INTO credential.credential_rotation (
                    id, tenant_id, old_credential_id,
                    replacement_credential_id,
                    initiator_identity_id, process_state,
                    checkpoint, failure_code, revision,
                    created_at, updated_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                rotation.id(),
                tenant.tenantId(),
                rotation.oldCredentialId(),
                rotation.replacementCredentialId(),
                rotation.initiatorIdentityId(),
                rotation.state().name(),
                rotation.checkpoint(),
                rotation.failureCode(),
                rotation.revision(),
                Timestamp.from(rotation.createdAt()),
                Timestamp.from(rotation.updatedAt()),
                timestamp(rotation.completedAt()));
    }

    @Override
    public Optional<CredentialRotation> findRotation(
            TenantContext tenant,
            UUID rotationId) {
        return jdbc.query("""
                SELECT id, old_credential_id,
                       replacement_credential_id,
                       initiator_identity_id, process_state,
                       checkpoint, failure_code, revision,
                       created_at, updated_at, completed_at
                FROM credential.credential_rotation
                WHERE tenant_id = ? AND id = ?
                """,
                (rs,row) -> rotation(rs),
                tenant.tenantId(),
                rotationId)
                .stream()
                .findFirst();
    }

    @Override
    public List<CredentialRotation> listRotations(
            TenantContext tenant,
            UUID oldCredentialId,
            RotationPosition after,
            int limit) {
        if (after == null) {
            return jdbc.query("""
                    SELECT id, old_credential_id,
                           replacement_credential_id,
                           initiator_identity_id, process_state,
                           checkpoint, failure_code, revision,
                           created_at, updated_at, completed_at
                    FROM credential.credential_rotation
                    WHERE tenant_id = ?
                      AND old_credential_id = ?
                    ORDER BY created_at DESC, id DESC
                    LIMIT ?
                    """,
                    (rs,row) -> rotation(rs),
                    tenant.tenantId(),
                    oldCredentialId,
                    limit);
        }
        return jdbc.query("""
                SELECT id, old_credential_id,
                       replacement_credential_id,
                       initiator_identity_id, process_state,
                       checkpoint, failure_code, revision,
                       created_at, updated_at, completed_at
                FROM credential.credential_rotation
                WHERE tenant_id = ?
                  AND old_credential_id = ?
                  AND (
                      created_at < ?
                      OR (created_at = ? AND id < ?)
                  )
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """,
                (rs,row) -> rotation(rs),
                tenant.tenantId(),
                oldCredentialId,
                Timestamp.from(after.createdAt()),
                Timestamp.from(after.createdAt()),
                after.id(),
                limit);
    }

    @Override
    public Optional<CredentialRotation> findOpenRotation(
            TenantContext tenant,
            UUID oldCredentialId) {
        return jdbc.query("""
                SELECT id, old_credential_id,
                       replacement_credential_id,
                       initiator_identity_id, process_state,
                       checkpoint, failure_code, revision,
                       created_at, updated_at, completed_at
                FROM credential.credential_rotation
                WHERE tenant_id = ?
                  AND old_credential_id = ?
                  AND process_state NOT IN (
                      'COMPLETED','FAILED',
                      'MANUAL_REQUIRED','FAILED_REMEDIATION')
                ORDER BY created_at DESC, id DESC
                LIMIT 1
                """,
                (rs,row) -> rotation(rs),
                tenant.tenantId(),
                oldCredentialId)
                .stream()
                .findFirst();
    }

    @Override
    public CredentialRotation updateRotation(
            TenantContext tenant,
            UUID rotationId,
            UUID replacementCredentialId,
            RotationState nextState,
            String checkpoint,
            String failureCode,
            long expectedRevision,
            Instant now,
            Instant completedAt) {
        int affected = jdbc.update("""
                UPDATE credential.credential_rotation
                SET replacement_credential_id = ?,
                    process_state = ?,
                    checkpoint = ?,
                    failure_code = ?,
                    revision = revision + 1,
                    updated_at = ?,
                    completed_at = ?
                WHERE tenant_id = ?
                  AND id = ?
                  AND revision = ?
                """,
                replacementCredentialId,
                nextState.name(),
                checkpoint,
                failureCode,
                Timestamp.from(now),
                timestamp(completedAt),
                tenant.tenantId(),
                rotationId,
                expectedRevision);
        if (affected != 1) {
            CredentialRotation current = findRotation(
                            tenant, rotationId)
                    .orElseThrow(() ->
                            new IllegalArgumentException(
                                    "CredentialRotation does not exist"));
            if (current.revision()
                    != expectedRevision) {
                throw new StaleWriteException(
                        "credential-rotation",
                        rotationId,
                        expectedRevision);
            }
            throw new IllegalStateException(
                    "CredentialRotation did not update");
        }
        return findRotation(
                tenant, rotationId).orElseThrow();
    }

    private static Credential credential(
            ResultSet rs) throws SQLException {
        return new Credential(
                rs.getObject("id", UUID.class),
                rs.getObject("principal_id", UUID.class),
                CredentialKind.valueOf(
                        rs.getString("credential_kind")),
                new SecretReference(
                        rs.getString(
                                "secret_provider_type"),
                        rs.getString(
                                "secret_reference_key")),
                CredentialState.valueOf(
                        rs.getString("lifecycle_state")),
                instant(rs.getTimestamp("valid_from")),
                instant(rs.getTimestamp("valid_until")),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                instant(rs.getTimestamp(
                        "compromised_at")),
                instant(rs.getTimestamp("revoked_at")),
                instant(rs.getTimestamp("expired_at")));
    }

    private static CredentialRotation rotation(
            ResultSet rs) throws SQLException {
        return new CredentialRotation(
                rs.getObject("id", UUID.class),
                rs.getObject(
                        "old_credential_id", UUID.class),
                rs.getObject(
                        "replacement_credential_id",
                        UUID.class),
                rs.getObject(
                        "initiator_identity_id", UUID.class),
                RotationState.valueOf(
                        rs.getString("process_state")),
                rs.getString("checkpoint"),
                rs.getString("failure_code"),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                instant(rs.getTimestamp("completed_at")));
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
