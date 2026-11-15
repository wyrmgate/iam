package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.application.GovernanceObservationEvaluationRepository.Phase;
import io.wyrmgate.iam.governance.application.GovernanceObservationReporter.ObservationCondition;
import io.wyrmgate.iam.governance.application.GovernanceFindingRepository.GovernanceFinding;
import io.wyrmgate.iam.identity.application.PrincipalObservationReferenceQuery;
import io.wyrmgate.iam.integration.application.IntegrationObservedAccessQuery;
import io.wyrmgate.iam.integration.application.IntegrationObservedAccessQuery.*;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One bounded page of normalized observation-derived findings. */
public final class ObservedAccessDriftEvaluationService {
    private static final int PAGE_SIZE = 100;
    private final IntegrationObservedAccessQuery observedAccess;
    private final PrincipalObservationReferenceQuery principalReferences;
    private final GovernanceObservationReporter reporter;
    private final GovernanceFindingRepository findings;

    public ObservedAccessDriftEvaluationService(
            IntegrationObservedAccessQuery observedAccess,
            PrincipalObservationReferenceQuery principalReferences,
            GovernanceObservationReporter reporter,
            GovernanceFindingRepository findings) {
        this.observedAccess = Objects.requireNonNull(observedAccess);
        this.principalReferences = Objects.requireNonNull(principalReferences);
        this.reporter = Objects.requireNonNull(reporter);
        this.findings = Objects.requireNonNull(findings);
    }

    public Progress evaluatePage(TenantContext tenant, UUID binding, Phase phase,
                                 String after, Instant now) {
        return switch (phase) {
            case ENTITLEMENTS -> {
                var page = observedAccess.entitlementPage(tenant, binding, after, PAGE_SIZE + 1);
                for (var fact : page.subList(0, Math.min(page.size(), PAGE_SIZE))) {
                    var condition = entitlementCondition(fact);
                    if (condition != null) reporter.observe(tenant, binding, condition, now);
                }
                yield page.size() > PAGE_SIZE
                        ? new Progress(phase, page.get(PAGE_SIZE - 1).providerEntitlementId())
                        : new Progress(Phase.GRANTS, null);
            }
            case GRANTS -> {
                var page = observedAccess.grantPage(tenant, binding, after, PAGE_SIZE + 1);
                for (var fact : page.subList(0, Math.min(page.size(), PAGE_SIZE))) {
                    var condition = grantCondition(tenant, fact);
                    if (condition != null) reporter.observe(tenant, binding, condition, now);
                }
                yield page.size() > PAGE_SIZE
                        ? new Progress(phase, page.get(PAGE_SIZE - 1).providerGrantId())
                        : new Progress(Phase.RESOLUTION, null);
            }
            case RESOLUTION -> {
                var page = findings.findOpenPage(tenant, binding, after, PAGE_SIZE + 1);
                for (var finding : page.subList(0, Math.min(page.size(), PAGE_SIZE))) {
                    if (resolved(tenant, binding, finding)) reporter.resolve(tenant, finding, now);
                }
                yield page.size() > PAGE_SIZE
                        ? new Progress(phase, page.get(PAGE_SIZE - 1).findingKey())
                        : new Progress(Phase.COMPLETED, null);
            }
            case COMPLETED -> new Progress(Phase.COMPLETED, null);
        };
    }

    private boolean resolved(TenantContext tenant, UUID binding, GovernanceFinding finding) {
        ObservationCondition current;
        if ("OBSERVED_ENTITLEMENT".equals(finding.subjectKind())) {
            var lookup = observedAccess.entitlement(tenant, binding, finding.providerStableId());
            if (lookup.presence() == Presence.UNKNOWN) return false;
            if (lookup.presence() == Presence.TRUSTED_ABSENT) return true;
            current = entitlementCondition(lookup.fact());
        } else if ("OBSERVED_GRANT".equals(finding.subjectKind())) {
            var lookup = observedAccess.grant(tenant, binding, finding.providerStableId());
            if (lookup.presence() == Presence.UNKNOWN) return false;
            if (lookup.presence() == Presence.TRUSTED_ABSENT) return true;
            current = grantCondition(tenant, lookup.fact());
        } else {
            return false;
        }
        return !Objects.equals(current, new ObservationCondition(finding.findingType(),
                finding.subjectKind(), finding.providerStableId(), finding.relatedProviderStableId()));
    }

    private static ObservationCondition entitlementCondition(ObservedEntitlementFact fact) {
        return fact.mappedEntitlementId() == null
                ? new ObservationCondition("UNMAPPED_PROVIDER_ENTITLEMENT", "OBSERVED_ENTITLEMENT",
                        fact.providerEntitlementId(), null)
                : null;
    }

    private ObservationCondition grantCondition(TenantContext tenant, ObservedGrantFact grant) {
        if (grant.mappedEntitlementId() == null) {
            return new ObservationCondition("UNMAPPED_PROVIDER_GRANT_ENTITLEMENT", "OBSERVED_GRANT",
                    grant.providerGrantId(), grant.providerEntitlementId());
        }
        if (grant.mappedPrincipalId() == null || principalReferences.validate(
                tenant, grant.mappedPrincipalId(), grant.applicationTargetId())
                != PrincipalObservationReferenceQuery.Validation.VALID) {
            return new ObservationCondition("UNRESOLVED_PROVIDER_GRANT_PRINCIPAL", "OBSERVED_GRANT",
                    grant.providerGrantId(), grant.providerPrincipalId());
        }
        return null;
    }

    public record Progress(Phase phase, String afterKey) {}
}
