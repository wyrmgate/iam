package io.wyrmgate.iam.integration.application;

import io.wyrmgate.iam.catalog.application.CatalogEntitlementReferenceQuery;
import io.wyrmgate.iam.catalog.application.CatalogEntitlementReferenceQuery.Validation;
import io.wyrmgate.iam.integration.application.IntegrationAdministrationRepository.ConnectorBinding;
import io.wyrmgate.iam.integration.application.IntegrationEntitlementMappingRepository.EntitlementObservationMapping;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class IntegrationEntitlementMappingService {

    private final IntegrationAdministrationRepository administration;
    private final IntegrationEntitlementMappingRepository mappings;
    private final CatalogEntitlementReferenceQuery catalog;
    private final IntegrationAdministrationFactSink facts;
    private final IntegrationObservedAccessFactSink observedAccessFacts;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;

    public IntegrationEntitlementMappingService(
            IntegrationAdministrationRepository administration,
            IntegrationEntitlementMappingRepository mappings,
            CatalogEntitlementReferenceQuery catalog,
            IntegrationAdministrationFactSink facts,
            IntegrationObservedAccessFactSink observedAccessFacts,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this.administration = Objects.requireNonNull(administration, "administration");
        this.mappings = Objects.requireNonNull(mappings, "mappings");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.facts = Objects.requireNonNull(facts, "facts");
        this.observedAccessFacts = Objects.requireNonNull(
                observedAccessFacts, "observedAccessFacts");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public EntitlementObservationMapping map(
            TenantContext tenant,
            UUID connectorBindingId,
            String providerStableId,
            UUID entitlementId,
            Instant now,
            UUID correlationId) {
        String providerId = requireText(providerStableId, "providerStableId");
        return transactions.required(() -> {
            ConnectorBinding binding = requireApplicationTargetBinding(tenant, connectorBindingId);
            if (!mappings.hasPresentObservedEntitlement(tenant, connectorBindingId, providerId)) {
                throw new IntegrationAdministrationException(
                        "observed_entitlement_not_found",
                        "A present observed entitlement with that provider identifier was not found.");
            }
            Validation validation =
                    catalog.validateActiveTargetEntitlement(tenant, entitlementId, binding.targetId());
            switch (validation) {
                case VALID -> { }
                case NOT_FOUND -> throw new IntegrationAdministrationException(
                        "entitlement_not_found", "Catalog Entitlement was not found.");
                case RETIRED -> throw new IntegrationAdministrationException(
                        "entitlement_retired", "Catalog Entitlement is retired.");
                case TARGET_MISMATCH -> throw new IntegrationAdministrationException(
                        "entitlement_target_mismatch",
                        "Catalog Entitlement does not belong to the connector binding ApplicationTarget.");
            }
            if (mappings.findActiveByProvider(tenant, connectorBindingId, providerId).isPresent()) {
                throw new IntegrationAdministrationException(
                        "mapping_already_exists",
                        "An active mapping already exists for that provider entitlement.");
            }
            EntitlementObservationMapping created = mappings.create(
                    tenant, ids.nextId(), connectorBindingId, providerId, entitlementId, now);
            facts.mappingChanged(
                    tenant, "integration.entitlement-observation-mapped",
                    created.id(), created.revision(), now, correlationId);
            observedAccessFacts.inputChanged(
                    tenant,
                    created.connectorBindingId(),
                    IntegrationObservedAccessFactSink.SourceKind.ENTITLEMENT_OBSERVATION_MAPPING,
                    created.id(),
                    created.revision(),
                    now,
                    correlationId);
            return created;
        });
    }

    public EntitlementObservationMapping unmap(
            TenantContext tenant,
            UUID mappingId,
            long expectedRevision,
            Instant now,
            UUID correlationId) {
        return transactions.required(() -> {
            EntitlementObservationMapping retired =
                    mappings.retire(tenant, mappingId, expectedRevision, now);
            facts.mappingChanged(
                    tenant, "integration.entitlement-observation-unmapped",
                    retired.id(), retired.revision(), now, correlationId);
            observedAccessFacts.inputChanged(
                    tenant,
                    retired.connectorBindingId(),
                    IntegrationObservedAccessFactSink.SourceKind.ENTITLEMENT_OBSERVATION_MAPPING,
                    retired.id(),
                    retired.revision(),
                    now,
                    correlationId);
            return retired;
        });
    }

    private ConnectorBinding requireApplicationTargetBinding(
            TenantContext tenant, UUID connectorBindingId) {
        ConnectorBinding binding = administration.findBinding(tenant, connectorBindingId)
                .orElseThrow(() -> new IntegrationAdministrationException(
                        "connector_binding_not_found", "Connector binding was not found."));
        if (!"ACTIVE".equals(binding.lifecycleState())
                || !"APPLICATION_TARGET".equals(binding.targetKind())) {
            throw new IntegrationAdministrationException(
                    "invalid_mapping_binding",
                    "Entitlement observation mapping requires an active ApplicationTarget connector binding.");
        }
        return binding;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
