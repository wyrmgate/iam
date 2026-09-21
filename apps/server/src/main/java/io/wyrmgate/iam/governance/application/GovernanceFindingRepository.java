package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GovernanceFindingRepository {

    Optional<GovernanceFinding> findByKey(TenantContext tenant, String findingKey);

    List<GovernanceFinding> findOpenByBinding(
            TenantContext tenant, UUID connectorBindingId);

    GovernanceFinding insert(
            TenantContext tenant,
            UUID id,
            String findingKey,
            String findingType,
            String subjectKind,
            UUID connectorBindingId,
            String providerStableId,
            String relatedProviderStableId,
            Instant observedAt);

    GovernanceFinding touchOpen(
            TenantContext tenant, UUID id, long expectedRevision, Instant observedAt);

    GovernanceFinding reopen(
            TenantContext tenant, UUID id, long expectedRevision, Instant observedAt);

    GovernanceFinding resolve(
            TenantContext tenant, UUID id, long expectedRevision, Instant resolvedAt);

    record GovernanceFinding(
            UUID id,
            String findingKey,
            String findingType,
            String subjectKind,
            UUID connectorBindingId,
            String providerStableId,
            String relatedProviderStableId,
            String lifecycleState,
            Instant firstObservedAt,
            Instant lastObservedAt,
            Instant resolvedAt,
            long revision,
            Instant createdAt,
            Instant updatedAt) {}
}
