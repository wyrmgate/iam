package io.wyrmgate.iam.integration.application;

import io.wyrmgate.iam.access.application.DesiredProvisioningStateQuery;
import io.wyrmgate.iam.access.application.DesiredStateProjectionRepository.DesiredPresence;
import io.wyrmgate.iam.access.application.DesiredStateProjectionRepository.DesiredGrantState;
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

    public PlanResult plan(
            TenantContext tenant,
            UUID desiredGrantId,
            long triggerRevision,
            UUID correlationId,
            UUID causationId,
            Instant now) {
        var snapshot = desired.desiredGrant(tenant, desiredGrantId);
        if (snapshot.status() == DesiredProvisioningStateQuery.Status.UNAVAILABLE) {
            return PlanResult.RETRY;
        }
        if (snapshot.status() == DesiredProvisioningStateQuery.Status.ABSENT) {
            return PlanResult.STALE_NOOP;
        }

        DesiredGrantState grant = snapshot.grant();
        if (grant.desiredRevision() != triggerRevision) {
            return PlanResult.STALE_NOOP;
        }

        List<GrantProvisioningRepository.TaskSpec> tasks =
                grant.desiredState() == DesiredPresence.PRESENT
                        ? presentTasks(tenant, grant)
                        : absentTasks(tenant, grant);
        if (tasks == null) return PlanResult.RETRY;
        if (tasks.isEmpty()) return PlanResult.NOOP;

        String planKey = "desired-grant:" + grant.id()
                + ":rev:" + grant.desiredRevision();
        provisioning.createPlan(
                tenant,
                planKey,
                grant.id(),
                grant.desiredRevision(),
                tasks,
                correlationId,
                causationId,
                now);
        return PlanResult.PLANNED;
    }

    private List<GrantProvisioningRepository.TaskSpec> presentTasks(
            TenantContext tenant,
            DesiredGrantState grant) {
        if (grant.principalId() == null) return null;

        var principal = principals.resolve(tenant, grant.principalId());
        if (principal.status() != PrincipalTechnicalReferenceQuery.Status.ACTIVE
                || !grant.identityId().equals(principal.identityId())
                || !grant.applicationTargetId().equals(
                        principal.applicationTargetId())) {
            return null;
        }

        var addTargets = provisioning.addTargets(
                tenant,
                grant.applicationTargetId(),
                grant.entitlementId(),
                principal.nativePrincipalKey());
        if (addTargets.size() != 1) return null;

        var addTarget = addTargets.getFirst();
        var add = task(
                grant,
                addTarget,
                "ADD_GRANT",
                null);

        List<GrantProvisioningRepository.TaskSpec> result =
                new ArrayList<>();
        result.add(add);

        for (var old : uniqueTargets(provisioning.priorSuccessfulAddTargets(
                tenant, grant.id()))) {
            if (!sameTechnicalTarget(old, addTarget)) {
                result.add(task(
                        grant,
                        old,
                        "REMOVE_GRANT",
                        old.connectorBindingId().equals(
                                addTarget.connectorBindingId())
                                ? add.taskKey()
                                : null));
            }
        }
        return List.copyOf(result);
    }

    private List<GrantProvisioningRepository.TaskSpec> absentTasks(
            TenantContext tenant,
            DesiredGrantState grant) {
        LinkedHashMap<String,GrantProvisioningRepository.TechnicalGrantTarget> targets =
                new LinkedHashMap<>();
        for (var prior : provisioning.priorSuccessfulAddTargets(
                tenant, grant.id())) {
            targets.put(targetKey(prior), prior);
        }

        List<String> principalKeys = principals.activeForIdentityTarget(
                        tenant,
                        grant.identityId(),
                        grant.applicationTargetId())
                .stream()
                .map(PrincipalTechnicalReferenceQuery.Result::nativePrincipalKey)
                .toList();
        for (var observed : provisioning.observedRemoveTargets(
                tenant,
                grant.applicationTargetId(),
                grant.entitlementId(),
                principalKeys)) {
            targets.put(targetKey(observed), observed);
        }

        return targets.values().stream()
                .map(target -> task(
                        grant, target, "REMOVE_GRANT", null))
                .toList();
    }

    private static List<GrantProvisioningRepository.TechnicalGrantTarget> uniqueTargets(
            List<GrantProvisioningRepository.TechnicalGrantTarget> values) {
        LinkedHashMap<String,GrantProvisioningRepository.TechnicalGrantTarget> unique =
                new LinkedHashMap<>();
        for (var value : values) unique.put(targetKey(value), value);
        return List.copyOf(unique.values());
    }

    private static GrantProvisioningRepository.TaskSpec task(
            DesiredGrantState grant,
            GrantProvisioningRepository.TechnicalGrantTarget target,
            String operation,
            String dependsOnTaskKey) {
        Map<String,Object> payload = new LinkedHashMap<>();
        payload.put("providerEntitlementId", target.providerEntitlementId());
        if (target.providerEntitlementVersion() != null) {
            payload.put(
                    "providerEntitlementVersion",
                    target.providerEntitlementVersion());
        }
        payload.put("providerPrincipalId", target.providerPrincipalId());

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
        return targetKey(left).equals(targetKey(right));
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
