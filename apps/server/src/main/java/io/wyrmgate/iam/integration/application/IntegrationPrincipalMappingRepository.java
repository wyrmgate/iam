package io.wyrmgate.iam.integration.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Integration-owned persistence port for provider-account to governed Principal interpretation. */
public interface IntegrationPrincipalMappingRepository {

    boolean hasPresentObservedPrincipal(
            TenantContext tenant,
            UUID connectorBindingId,
            String providerStableId);

    Optional<PrincipalObservationMapping> findPrincipalMapping(
            TenantContext tenant,
            UUID mappingId);

    Optional<PrincipalObservationMapping> findActivePrincipalMappingByProvider(
            TenantContext tenant,
            UUID connectorBindingId,
            String providerStableId);

    PrincipalObservationMapping createPrincipalMapping(
            TenantContext tenant,
            UUID id,
            UUID connectorBindingId,
            String providerStableId,
            UUID principalId,
            Instant now);

    PrincipalObservationMapping retirePrincipalMapping(
            TenantContext tenant,
            UUID id,
            long expectedRevision,
            Instant now);

    record PrincipalObservationMapping(
            UUID id,
            UUID connectorBindingId,
            String providerStableId,
            UUID principalId,
            String lifecycleState,
            long revision,
            Instant createdAt,
            Instant updatedAt,
            Instant retiredAt) {}
}
