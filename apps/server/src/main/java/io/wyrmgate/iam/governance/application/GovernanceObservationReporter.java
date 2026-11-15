package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.application.GovernanceFindingRepository.GovernanceFinding;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;

/** Positive conditions and exact resolutions; page omission is not resolution evidence. */
public interface GovernanceObservationReporter {
    void observe(TenantContext tenant, UUID connectorBindingId,
                 ObservationCondition condition, Instant observedAt);
    void resolve(TenantContext tenant, GovernanceFinding finding, Instant observedAt);

    record ObservationCondition(String findingType, String subjectKind,
                                String providerStableId, String relatedProviderStableId) {}
}
