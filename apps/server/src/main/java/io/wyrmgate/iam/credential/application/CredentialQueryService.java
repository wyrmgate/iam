package io.wyrmgate.iam.credential.application;

import io.wyrmgate.iam.credential.application.CredentialQueryModels.CredentialPosition;
import io.wyrmgate.iam.credential.application.CredentialQueryModels.Page;
import io.wyrmgate.iam.credential.application.CredentialQueryModels.RotationPosition;
import io.wyrmgate.iam.credential.domain.CredentialModels.Credential;
import io.wyrmgate.iam.credential.domain.CredentialModels.CredentialRotation;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class CredentialQueryService {

    private final CredentialRepository repository;

    public CredentialQueryService(CredentialRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public Optional<Credential> findCredential(
            TenantContext tenant,
            UUID credentialId) {
        return repository.findCredential(tenant, credentialId);
    }

    public Page<Credential,CredentialPosition> listCredentials(
            TenantContext tenant,
            UUID principalId,
            CredentialPosition after,
            int limit) {
        validateLimit(limit);
        List<Credential> rows = repository.listCredentials(
                tenant, principalId, after, limit + 1);
        boolean more = rows.size() > limit;
        List<Credential> items = more
                ? List.copyOf(rows.subList(0, limit))
                : List.copyOf(rows);
        CredentialPosition next = more
                ? position(items.get(items.size() - 1))
                : null;
        return new Page<>(items, next);
    }

    public Optional<CredentialRotation> findRotation(
            TenantContext tenant,
            UUID rotationId) {
        return repository.findRotation(tenant, rotationId);
    }

    public Page<CredentialRotation,RotationPosition> listRotations(
            TenantContext tenant,
            UUID oldCredentialId,
            RotationPosition after,
            int limit) {
        validateLimit(limit);
        List<CredentialRotation> rows = repository.listRotations(
                tenant, oldCredentialId, after, limit + 1);
        boolean more = rows.size() > limit;
        List<CredentialRotation> items = more
                ? List.copyOf(rows.subList(0, limit))
                : List.copyOf(rows);
        RotationPosition next = more
                ? position(items.get(items.size() - 1))
                : null;
        return new Page<>(items, next);
    }

    private static CredentialPosition position(Credential value) {
        return new CredentialPosition(
                value.state(), value.createdAt(), value.id());
    }

    private static RotationPosition position(CredentialRotation value) {
        return new RotationPosition(value.createdAt(), value.id());
    }

    private static void validateLimit(int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and 200");
        }
    }
}
