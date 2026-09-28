package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.application.ApprovalModels.ApprovalCase;
import io.wyrmgate.iam.governance.application.ApprovalModels.SubjectKind;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class ApprovalQueryService implements ApprovalResultQuery {

    private final ApprovalRepository repository;

    public ApprovalQueryService(ApprovalRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public Optional<ApprovalCase> findBySubject(
            TenantContext tenant,
            SubjectKind subjectKind,
            UUID subjectId) {
        return repository.findLatestBySubject(
                tenant, subjectKind, subjectId);
    }
}
