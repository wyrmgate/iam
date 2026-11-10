package io.wyrmgate.iam.integration.application;

import io.wyrmgate.iam.identity.application.PrincipalObservationReferenceQuery;
import io.wyrmgate.iam.identity.application.PrincipalObservationReferenceQuery.Validation;
import io.wyrmgate.iam.integration.application.IntegrationAdministrationRepository.ConnectorBinding;
import io.wyrmgate.iam.integration.application.IntegrationPrincipalMappingRepository.PrincipalObservationMapping;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** ADR-0050 explicit provider-account to governed Principal mapping commands. */
public final class IntegrationPrincipalMappingService {

    private final IntegrationAdministrationRepository administration;
    private final IntegrationPrincipalMappingRepository mappings;
    private final PrincipalObservationReferenceQuery principals;
    private final IntegrationAdministrationFactSink facts;
    private final IntegrationObservedAccessFactSink observedAccessFacts;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;

    public IntegrationPrincipalMappingService(
            IntegrationAdministrationRepository administration,
            IntegrationPrincipalMappingRepository mappings,
            PrincipalObservationReferenceQuery principals,
            IntegrationAdministrationFactSink facts,
            IntegrationObservedAccessFactSink observedAccessFacts,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this.administration = Objects.requireNonNull(administration, "administration");
        this.mappings = Objects.requireNonNull(mappings, "mappings");
        this.principals = Objects.requireNonNull(principals, "principals");
        this.facts = Objects.requireNonNull(facts, "facts");
        this.observedAccessFacts = Objects.requireNonNull(observedAccessFacts, "observedAccessFacts");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public PrincipalObservationMapping map(
            TenantContext tenant,
            UUID connectorBindingId,
            String providerStableId,
            UUID principalId,
            Instant now,
            UUID correlationId) {
        String providerId = requireText(providerStableId, "providerStableId");
        Objects.requireNonNull(principalId, "principalId");
        return transactions.required(() -> {
            ConnectorBinding binding = requireApplicationTargetBinding(tenant, connectorBindingId);
            if (!mappings.hasPresentObservedPrincipal(tenant, connectorBindingId, providerId)) {
                throw new IntegrationAdministrationException(
                        "observed_principal_not_found",
                        "A present observed Principal with that stable provider identifier was not found.");
            }
            Validation validation = principals.validate(tenant, principalId, binding.targetId());
            switch (validation) {
                case VALID -> { }
                case NOT_FOUND -> throw new IntegrationAdministrationException(
                        "principal_not_found",
                        "The governed Principal was not found in the tenant.");
                case TARGET_MISMATCH -> throw new IntegrationAdministrationException(
                        "principal_target_mismatch",
                        "The governed Principal does not belong to the connector binding ApplicationTarget.");
            }
            if (mappings.findActivePrincipalMappingByProvider(
                    tenant, connectorBindingId, providerId).isPresent()) {
                throw new IntegrationAdministrationException(
                        "principal_mapping_already_exists",
                        "An active Principal observation mapping already exists for that provider account.");
            }

            PrincipalObservationMapping created = mappings.createPrincipalMapping(
                    tenant, ids.nextId(), connectorBindingId, providerId, principalId, now);
            facts.principalMappingChanged(
                    tenant,
                    "integration.principal-observation-mapped",
                    created.id(),
                    created.revision(),
                    now,
                    correlationId);
            observedAccessFacts.inputChanged(
                    tenant,
                    created.connectorBindingId(),
                    IntegrationObservedAccessFactSink.SourceKind.PRINCIPAL_OBSERVATION_MAPPING,
                    created.id(),
                    created.revision(),
                    now,
                    correlationId);
            return created;
        });
    }

    public PrincipalObservationMapping unmap(
            TenantContext tenant,
            UUID mappingId,
            long expectedRevision,
            Instant now,
            UUID correlationId) {
        return transactions.required(() -> {
            PrincipalObservationMapping retired =
                    mappings.retirePrincipalMapping(tenant, mappingId, expectedRevision, now);
            facts.principalMappingChanged(
                    tenant,
                    "integration.principal-observation-unmapped",
                    retired.id(),
                    retired.revision(),
                    now,
                    correlationId);
            observedAccessFacts.inputChanged(
                    tenant,
                    retired.connectorBindingId(),
                    IntegrationObservedAccessFactSink.SourceKind.PRINCIPAL_OBSERVATION_MAPPING,
                    retired.id(),
                    retired.revision(),
                    now,
                    correlationId);
            return retired;
        });
    }

    private ConnectorBinding requireApplicationTargetBinding(
            TenantContext tenant,
            UUID connectorBindingId) {
        ConnectorBinding binding = administration.findBinding(tenant, connectorBindingId)
                .orElseThrow(() -> new IntegrationAdministrationException(
                        "connector_binding_not_found",
                        "Connector binding was not found."));
        if (!"ACTIVE".equals(binding.lifecycleState())
                || !"APPLICATION_TARGET".equals(binding.targetKind())) {
            throw new IntegrationAdministrationException(
                    "invalid_principal_mapping_binding",
                    "Principal observation mapping requires an active ApplicationTarget connector binding.");
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
