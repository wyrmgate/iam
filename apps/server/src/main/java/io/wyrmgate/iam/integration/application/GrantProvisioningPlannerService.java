package io.wyrmgate.iam.integration.application;

import io.wyrmgate.iam.access.application.DesiredProvisioningStateQuery;
import io.wyrmgate.iam.access.application.DesiredStateProjectionRepository.DesiredGrantState;
import io.wyrmgate.iam.access.application.DesiredStateProjectionRepository.DesiredPresence;
import io.wyrmgate.iam.identity.application.PrincipalTechnicalReferenceQuery;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class GrantProvisioningPlannerService {

    private final DesiredProvisioningStateQuery desired;
    private final PrincipalTechnicalReferenceQuery principals;
    private final GrantProvisioningRepository provisioning;

    public GrantProvisioningPlannerService(
            DesiredProvisioningStateQuery desired,
            PrincipalTechnicalReferenceQuery principals,
            GrantProvisioningRepository provisioning) {
        this.desired = Objects.requireNonNull(desired, "desired");
        this.principals = Objects.requireNonNull(principals, "principals");
        this.provisioning = Objects.requireNonNull(provisioning, "provisioning");
    }

    private static final int CLEANUP_PAGE_SIZE = 100;

    public PageResult planPage(
            TenantContext tenant, UUID desiredGrantId, long triggerRevision,
            UUID correlationId, UUID causationId, Instant now,
            GrantProvisioningRepository.CleanupCursor after, boolean hadRemoval,
            boolean continuation) {
        var snapshot = desired.desiredGrant(tenant, desiredGrantId);
        if (snapshot.status() == DesiredProvisioningStateQuery.Status.UNAVAILABLE) {
            return PageResult.finished(PlanResult.RETRY);
        }
        if (snapshot.status() == DesiredProvisioningStateQuery.Status.ABSENT
                || snapshot.grant().desiredRevision() != triggerRevision) {
            return PageResult.finished(PlanResult.STALE_NOOP);
        }
        DesiredGrantState grant = snapshot.grant();
        boolean present = grant.desiredState() == DesiredPresence.PRESENT;
        boolean unresolved = present && grant.principalId() == null;
        if (provisioning.hasUninterpretablePriorEvidence(tenant, grant.id(), grant.entitlementId())) {
            return PageResult.finished(PlanResult.RETRY);
        }

        GrantProvisioningRepository.TechnicalGrantTarget addTarget = null;
        GrantProvisioningRepository.TaskSpec add = null;
        if (present && !unresolved) {
            var principal = principals.resolve(tenant, grant.principalId());
            if (principal.status() != PrincipalTechnicalReferenceQuery.Status.ACTIVE
                    || !grant.identityId().equals(principal.identityId())
                    || !grant.applicationTargetId().equals(principal.applicationTargetId())) {
                return PageResult.finished(PlanResult.RETRY);
            }
            var routes = provisioning.addTargets(tenant, grant.applicationTargetId(),
                    grant.entitlementId(), principal.nativePrincipalKey());
            if (routes.size() != 1) return PageResult.finished(PlanResult.RETRY);
            addTarget = routes.getFirst().withPrincipalId(grant.principalId());
            add = task(grant, addTarget, "ADD_GRANT", null);
        }

        var page = provisioning.cleanupTargetsPage(tenant, grant.id(), grant.applicationTargetId(),
                grant.entitlementId(), present ? null : grant.principalId(), after, CLEANUP_PAGE_SIZE);
        boolean more = page.size() > CLEANUP_PAGE_SIZE;
        var targets = more ? page.subList(0, CLEANUP_PAGE_SIZE) : page;
        var removals = new ArrayList<GrantProvisioningRepository.TaskSpec>();
        boolean waitsForAdd = false;
        for (var candidate : targets) {
            var old = candidate.target();
            if (old.principalId() == null) return PageResult.finished(PlanResult.RETRY);
            if (candidate.includesObservation()) {
                var mappedPrincipal = principals.resolve(tenant, old.principalId());
                if (mappedPrincipal.status() == PrincipalTechnicalReferenceQuery.Status.UNAVAILABLE
                        || !grant.applicationTargetId().equals(mappedPrincipal.applicationTargetId())) {
                    return PageResult.finished(PlanResult.RETRY);
                }
            }
            if (addTarget != null && sameTechnicalTarget(old, addTarget)) {
                if (!old.principalId().equals(addTarget.principalId())) {
                    return PageResult.finished(PlanResult.RETRY);
                }
                continue;
            }
            if (addTarget == null || !old.principalId().equals(addTarget.principalId())) {
                var demand = desired.technicalGrantDemand(tenant, grant.applicationTargetId(),
                        grant.entitlementId(), old.principalId());
                if (demand == DesiredProvisioningStateQuery.TechnicalGrantDemand.UNAVAILABLE) {
                    return PageResult.finished(PlanResult.RETRY);
                }
                if (demand == DesiredProvisioningStateQuery.TechnicalGrantDemand.REQUIRED) continue;
            }
            var remove = task(grant, old, "REMOVE_GRANT", null);
            if (provisioning.taskState(tenant, remove.taskKey()).isPresent()) {
                hadRemoval = true;
                continue;
            }
            if (addTarget != null && old.connectorBindingId().equals(addTarget.connectorBindingId())) {
                waitsForAdd = true;
            }
            removals.add(remove);
        }

        String planKey = "desired-grant:" + grant.id() + ":rev:" + grant.desiredRevision();
        if (add != null && provisioning.taskState(tenant, add.taskKey()).isEmpty()) {
            provisioning.createPlan(tenant, planKey, grant.id(), grant.desiredRevision(),
                    List.of(add), correlationId, causationId, now);
        }
        // Preserve same-binding ADD-before-REMOVE ordering without a cross-job
        // FK: wait for the exact replacement ADD task to succeed. Do not advance
        // past a blocked route.
        if (waitsForAdd && !"SUCCEEDED".equals(
                provisioning.taskState(tenant, add.taskKey()).orElse(null))) {
            return continuation ? PageResult.finished(PlanResult.RETRY)
                    : new PageResult(PlanResult.PLANNED, true, after, hadRemoval, true);
        }

        for (var remove : removals) {
            // A deterministic per-route plan is independent of page boundaries.
            provisioning.createPlan(tenant, remove.taskKey(), grant.id(), grant.desiredRevision(),
                    List.of(remove), correlationId, causationId, now);
            hadRemoval = true;
        }

        if (more) {
            return new PageResult(PlanResult.PLANNED, true,
                    GrantProvisioningRepository.CleanupCursor.from(targets.getLast().target()),
                    hadRemoval, false);
        }
        if (unresolved && !hadRemoval) {
            // A multi-page scan with no reducible realization restarts so a
            // later change in coalesced demand on an earlier route isn't missed.
            return after == null ? PageResult.finished(PlanResult.RETRY)
                    : new PageResult(PlanResult.NOOP, true, null, false, true);
        }
        return PageResult.finished(add != null || hadRemoval ? PlanResult.PLANNED : PlanResult.NOOP);
    }

    public record PageResult(PlanResult outcome, boolean more,
                             GrantProvisioningRepository.CleanupCursor after,
                             boolean hadRemoval, boolean delayed) {
        static PageResult finished(PlanResult outcome) {
            return new PageResult(outcome, false, null, false, false);
        }
    }

    private static GrantProvisioningRepository.TaskSpec task(
            DesiredGrantState grant,
            GrantProvisioningRepository.TechnicalGrantTarget target,
            String operation,
            String dependsOnTaskKey) {
        Map<String,Object> payload = new LinkedHashMap<>();
        payload.put(
                "providerEntitlementId",
                target.providerEntitlementId());
        if (target.providerEntitlementVersion() != null) {
            payload.put(
                    "providerEntitlementVersion",
                    target.providerEntitlementVersion());
        }
        payload.put(
                "providerPrincipalId",
                target.providerPrincipalId());
        payload.put(
                "entitlementId",
                grant.entitlementId().toString());
        if (target.principalId() != null) {
            payload.put(
                    "principalId",
                    target.principalId().toString());
        }

        String technicalKey = targetKey(target);
        String fingerprint = RequestFingerprint.sha256(
                (grant.id() + "|" + grant.desiredRevision()
                        + "|" + operation + "|" + technicalKey)
                        .getBytes(StandardCharsets.UTF_8))
                .value();
        String taskKey = "dg:" + grant.id()
                + ":r" + grant.desiredRevision()
                + ":" + operation + ":" + fingerprint.substring(
                        Math.max(0, fingerprint.length() - 32));

        return new GrantProvisioningRepository.TaskSpec(
                taskKey,
                target.connectorBindingId(),
                target.contractId(),
                target.contractVersion(),
                operation,
                payload,
                dependsOnTaskKey);
    }

    private static boolean sameTechnicalTarget(
            GrantProvisioningRepository.TechnicalGrantTarget left,
            GrantProvisioningRepository.TechnicalGrantTarget right) {
        return GrantProvisioningRepository.CleanupCursor.from(left)
                .equals(GrantProvisioningRepository.CleanupCursor.from(right));
    }

    private static String targetKey(
            GrantProvisioningRepository.TechnicalGrantTarget target) {
        return target.connectorBindingId()
                + "|" + target.providerEntitlementId()
                + "|" + target.providerPrincipalId();
    }

    public enum PlanResult {
        PLANNED,
        NOOP,
        STALE_NOOP,
        RETRY
    }
}
