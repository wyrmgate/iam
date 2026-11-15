package io.wyrmgate.iam.integration.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Bounded semantic reads of Integration-owned observations and exact absence evidence. */
public interface IntegrationObservedAccessQuery {
    long currentGeneration(TenantContext tenant, UUID connectorBindingId);

    List<ObservedEntitlementFact> entitlementPage(
            TenantContext tenant, UUID connectorBindingId, String afterProviderId, int limit);
    List<ObservedGrantFact> grantPage(
            TenantContext tenant, UUID connectorBindingId, String afterProviderId, int limit);

    Observation<ObservedEntitlementFact> entitlement(
            TenantContext tenant, UUID connectorBindingId, String providerId);
    Observation<ObservedGrantFact> grant(
            TenantContext tenant, UUID connectorBindingId, String providerId);

    /** One deterministic present mapped grant witness per requested Entitlement across bindings. */
    List<ObservedGrantFact> mappedGrantWitnesses(TenantContext tenant, java.util.Set<UUID> principalIds,
            java.util.Set<UUID> entitlementIds);

    List<UUID> activeApplicationTargetBindings(
            TenantContext tenant, UUID applicationTargetId);

    enum Presence { PRESENT, TRUSTED_ABSENT, UNKNOWN }

    record Observation<T>(Presence presence, T fact) {
        public Observation {
            Objects.requireNonNull(presence, "presence");
            if ((presence == Presence.PRESENT) != (fact != null)) {
                throw new IllegalArgumentException("only PRESENT carries an observation");
            }
        }
    }

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
            UUID mappedPrincipalId,
            Instant observedAt) {}
}
