package io.wyrmgate.iam.integration.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Consumer-facing semantic query over current provider observations and explicit entitlement mappings. */
public interface IntegrationObservedAccessQuery {

    List<ObservedEntitlementFact> currentEntitlements(
            TenantContext tenant, UUID connectorBindingId);

    List<ObservedGrantFact> currentGrants(
            TenantContext tenant, UUID connectorBindingId);

    List<UUID> activeApplicationTargetBindings(
            TenantContext tenant, UUID applicationTargetId);

    record ObservedEntitlementFact(
            UUID connectorBindingId,
            String providerEntitlementId,
            UUID mappedEntitlementId,
            Instant observedAt) {}

    record ObservedGrantFact(
            UUID connectorBindingId,
            UUID applicationTargetId,
            String providerGrantId,
            String providerPrincipalId,
            String providerEntitlementId,
            UUID mappedEntitlementId,
            Instant observedAt) {}
}
