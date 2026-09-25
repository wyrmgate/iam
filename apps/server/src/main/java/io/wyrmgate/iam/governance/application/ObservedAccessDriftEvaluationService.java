package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.application.GovernanceObservationReporter.ObservationCondition;
import io.wyrmgate.iam.identity.application.PrincipalResolutionQuery;
import io.wyrmgate.iam.integration.application.IntegrationObservedAccessQuery;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Governance-owned interpretation of normalized provider observations into actionable findings. */
public final class ObservedAccessDriftEvaluationService {

    private final IntegrationObservedAccessQuery observedAccess;
    private final PrincipalResolutionQuery principalResolution;
    private final GovernanceObservationReporter reporter;

    public ObservedAccessDriftEvaluationService(
            IntegrationObservedAccessQuery observedAccess,
            PrincipalResolutionQuery principalResolution,
            GovernanceObservationReporter reporter) {
        this.observedAccess = Objects.requireNonNull(observedAccess, "observedAccess");
        this.principalResolution = Objects.requireNonNull(
                principalResolution, "principalResolution");
        this.reporter = Objects.requireNonNull(reporter, "reporter");
    }

    public void evaluate(
            TenantContext tenant, UUID connectorBindingId, Instant observedAt) {
        Set<ObservationCondition> conditions = new LinkedHashSet<>();

        for (var entitlement :
                observedAccess.currentEntitlements(tenant, connectorBindingId)) {
            if (entitlement.mappedEntitlementId() == null) {
                conditions.add(new ObservationCondition(
                        "UNMAPPED_PROVIDER_ENTITLEMENT",
                        "OBSERVED_ENTITLEMENT",
                        entitlement.providerEntitlementId(),
                        null));
            }
        }

        for (var grant : observedAccess.currentGrants(tenant, connectorBindingId)) {
            if (grant.mappedEntitlementId() == null) {
                conditions.add(new ObservationCondition(
                        "UNMAPPED_PROVIDER_GRANT_ENTITLEMENT",
                        "OBSERVED_GRANT",
                        grant.providerGrantId(),
                        grant.providerEntitlementId()));
            } else {
                var resolution = principalResolution.resolve(
                        tenant,
                        grant.applicationTargetId(),
                        grant.providerPrincipalId());
                if (resolution.status()
                        != PrincipalResolutionQuery.Status.RESOLVED) {
                    conditions.add(new ObservationCondition(
                            "UNRESOLVED_PROVIDER_GRANT_PRINCIPAL",
                            "OBSERVED_GRANT",
                            grant.providerGrantId(),
                            grant.providerPrincipalId()));
                }
            }
        }

        reporter.report(
                tenant, connectorBindingId, Set.copyOf(conditions), observedAt);
    }
}
