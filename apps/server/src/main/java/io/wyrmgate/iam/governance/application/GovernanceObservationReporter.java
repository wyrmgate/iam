package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Semantic Governance command for reporting the current observation-derived issue set for one scope. */
public interface GovernanceObservationReporter {

    void report(
            TenantContext tenant,
            UUID connectorBindingId,
            Set<ObservationCondition> currentConditions,
            Instant observedAt);

    record ObservationCondition(
            String findingType,
            String subjectKind,
            String providerStableId,
            String relatedProviderStableId) {}
}
