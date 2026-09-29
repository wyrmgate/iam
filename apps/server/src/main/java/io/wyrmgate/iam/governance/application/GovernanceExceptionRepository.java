package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.domain.GovernanceExceptionModels.*;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface GovernanceExceptionRepository {

    void insert(
            TenantContext tenant,
            GovernanceException exception);

    Optional<GovernanceException> findById(
            TenantContext tenant,
            UUID exceptionId);

    List<GovernanceException> findEffective(
            TenantContext tenant,
            UUID subjectIdentityId,
            Set<UUID> sodRuleIds,
            Instant at);

    GovernanceException updateState(
            TenantContext tenant,
            UUID exceptionId,
            LifecycleState state,
            long expectedRevision,
            Instant now,
            Instant approvedAt,
            Instant rejectedAt,
            Instant revokedAt,
            Instant expiredAt);
}
