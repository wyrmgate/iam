package io.wyrmgate.iam.credential.application;

import io.wyrmgate.iam.credential.domain.CredentialModels.*;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CredentialRepository {

    void insertCredential(
            TenantContext tenant,
            Credential credential);

    Optional<Credential> findCredential(
            TenantContext tenant,
            UUID credentialId);

    Credential updateCredentialState(
            TenantContext tenant,
            UUID credentialId,
            CredentialState state,
            long expectedRevision,
            Instant now,
            Instant compromisedAt,
            Instant revokedAt,
            Instant expiredAt);

    void insertRotation(
            TenantContext tenant,
            CredentialRotation rotation);

    Optional<CredentialRotation> findRotation(
            TenantContext tenant,
            UUID rotationId);

    CredentialRotation updateRotation(
            TenantContext tenant,
            UUID rotationId,
            UUID replacementCredentialId,
            RotationState nextState,
            String checkpoint,
            String failureCode,
            long expectedRevision,
            Instant now,
            Instant completedAt);
}
