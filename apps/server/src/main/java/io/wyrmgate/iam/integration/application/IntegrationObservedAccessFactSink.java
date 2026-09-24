package io.wyrmgate.iam.integration.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;

/**
 * Integration-owned internal fact boundary used to trigger observation-derived
 * Governance evaluation after Integration state has committed.
 */
public interface IntegrationObservedAccessFactSink {

    String OBSERVED_ACCESS_INPUT_CHANGED = "integration.observed-access-input-changed";


    void inputChanged(
            TenantContext tenant,
            UUID connectorBindingId,
            SourceKind sourceKind,
            UUID sourceId,
            long sourceRevision,
            Instant occurredAt,
            UUID correlationId);

    enum SourceKind {
        RECONCILIATION_RUN("reconciliation-run"),
        ENTITLEMENT_OBSERVATION_MAPPING("entitlement-observation-mapping");

        private final String aggregateType;

        SourceKind(String aggregateType) {
            this.aggregateType = aggregateType;
        }

        public String aggregateType() {
            return aggregateType;
        }
    }
}
