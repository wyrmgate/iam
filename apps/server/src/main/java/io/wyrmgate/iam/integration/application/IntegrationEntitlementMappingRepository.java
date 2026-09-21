package io.wyrmgate.iam.integration.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IntegrationEntitlementMappingRepository {

    boolean hasPresentObservedEntitlement(
            TenantContext tenant, UUID connectorBindingId, String providerStableId);

    Optional<EntitlementObservationMapping> find(
            TenantContext tenant, UUID mappingId);

    Optional<EntitlementObservationMapping> findActiveByProvider(
            TenantContext tenant, UUID connectorBindingId, String providerStableId);

    EntitlementObservationMapping create(
            TenantContext tenant,
            UUID id,
            UUID connectorBindingId,
            String providerStableId,
            UUID entitlementId,
            Instant now);

    EntitlementObservationMapping retire(
            TenantContext tenant,
            UUID id,
            long expectedRevision,
            Instant now);

    record EntitlementObservationMapping(
            UUID id,
            UUID connectorBindingId,
            String providerStableId,
            UUID entitlementId,
            String lifecycleState,
            long revision,
            Instant createdAt,
            Instant updatedAt,
            Instant retiredAt) {}
}
