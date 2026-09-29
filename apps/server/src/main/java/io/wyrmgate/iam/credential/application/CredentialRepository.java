package io.wyrmgate.iam.credential.application;

import io.wyrmgate.iam.credential.application.CredentialQueryModels.CredentialPosition;
import io.wyrmgate.iam.credential.application.CredentialQueryModels.RotationPosition;
import io.wyrmgate.iam.credential.domain.CredentialModels.*;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CredentialRepository {

    void insertCredential(
            TenantContext tenant,
            Credential credential);

    Optional<Credential> findCredential(
            TenantContext tenant,
            UUID credentialId);

    List<Credential> listCredentials(
            TenantContext tenant,
            UUID principalId,
            CredentialPosition after,
            int limit);

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

    List<CredentialRotation> listRotations(
            TenantContext tenant,
            UUID oldCredentialId,
            RotationPosition after,
            int limit);

    Optional<CredentialRotation> findOpenRotation(
            TenantContext tenant,
            UUID oldCredentialId);

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
