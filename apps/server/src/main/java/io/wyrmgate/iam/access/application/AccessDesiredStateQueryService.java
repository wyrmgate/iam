package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;

public final class AccessDesiredStateQueryService implements DesiredAccessStateQuery {

    private final DesiredStateProjectionRepository repository;

    public AccessDesiredStateQueryService(DesiredStateProjectionRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public Freshness current(TenantContext tenant, SubjectKind subjectKind, UUID subjectId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(subjectKind, "subjectKind");
        Objects.requireNonNull(subjectId, "subjectId");
        try {
            return switch (subjectKind) {
                case DESIRED_PRINCIPAL -> repository.principalFreshness(tenant, subjectId);
                case DESIRED_GRANT -> repository.grantFreshness(tenant, subjectId);
            };
        } catch (RuntimeException unavailable) {
            return Freshness.unavailable();
        }
    }
}
