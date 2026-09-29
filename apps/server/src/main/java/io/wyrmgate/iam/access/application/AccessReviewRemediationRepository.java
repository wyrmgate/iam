package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.application.AccessReviewRemediationCommand.Result;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AccessReviewRemediationRepository {

    Optional<Result> find(
            TenantContext tenant,
            UUID reviewRemediationId);

    void insert(
            TenantContext tenant,
            UUID reviewRemediationId,
            UUID accessAssignmentId,
            Result result,
            Instant appliedAt);
}
