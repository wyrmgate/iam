package io.wyrmgate.iam.integration.application;

import io.wyrmgate.iam.access.application.DesiredProvisioningStateQuery;
import io.wyrmgate.iam.access.application.DesiredStateProjectionRepository.DesiredPresence;
import io.wyrmgate.iam.access.application.DesiredStateProjectionRepository.DesiredPrincipalState;
import io.wyrmgate.iam.identity.application.PrincipalProvisioningProfileQuery;
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

public final class PrincipalProvisioningPlannerService {

    private final DesiredProvisioningStateQuery desired;
    private final PrincipalTechnicalReferenceQuery principals;
    private final PrincipalProvisioningProfileQuery profiles;
    private final PrincipalProvisioningRepository provisioning;

    public PrincipalProvisioningPlannerService(
            DesiredProvisioningStateQuery desired,
            PrincipalTechnicalReferenceQuery principals,
            PrincipalProvisioningProfileQuery profiles,
            PrincipalProvisioningRepository provisioning) {
        this.desired = Objects.requireNonNull(desired, "desired");
        this.principals = Objects.requireNonNull(principals, "principals");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.provisioning = Objects.requireNonNull(provisioning, "provisioning");
    }

    public PlanResult plan(
            TenantContext tenant,
            UUID desiredPrincipalId,
            long triggerRevision,
            UUID correlationId,
            UUID causationId,
            Instant now) {
        var snapshot = desired.desiredPrincipal(tenant, desiredPrincipalId);
        if (snapshot.status() == DesiredProvisioningStateQuery.Status.UNAVAILABLE) {
            return PlanResult.RETRY;
        }
        if (snapshot.status() == DesiredProvisioningStateQuery.Status.ABSENT) {
            return PlanResult.STALE_NOOP;
        }
        DesiredPrincipalState principal = snapshot.principal();
        if (principal.desiredRevision() != triggerRevision) {
            return PlanResult.STALE_NOOP;
        }

        List<PrincipalProvisioningRepository.TaskSpec> tasks =
                principal.desiredState() == DesiredPresence.PRESENT
                        ? presentTasks(tenant, principal)
                        : absentTasks(tenant, principal);
        if (tasks == null) return PlanResult.RETRY;
        if (tasks.isEmpty()) return PlanResult.NOOP;

        provisioning.createPlan(
                tenant,
                "desired-principal:" + principal.id() + ":rev:" + principal.desiredRevision(),
                principal.id(),
                principal.desiredRevision(),
                tasks,
                correlationId,
                causationId,
                now);
        return PlanResult.PLANNED;
    }

    private List<PrincipalProvisioningRepository.TaskSpec> presentTasks(
            TenantContext tenant,
            DesiredPrincipalState desiredPrincipal) {
        List<PrincipalTechnicalReferenceQuery.Result> existing =
                principals.forIdentityTarget(
                        tenant,
                        desiredPrincipal.identityId(),
                        desiredPrincipal.applicationTargetId());
        if (existing.size() > 1) return null;
        if (existing.size() == 1
                && existing.getFirst().status()
                        == PrincipalTechnicalReferenceQuery.Status.ACTIVE) {
            return List.of();
        }

        if (existing.size() == 1) {
            var known = uniqueTargets(provisioning.existingTargets(
                    tenant,
                    desiredPrincipal.applicationTargetId(),
                    desiredPrincipal.id(),
                    existing.getFirst().nativePrincipalKey()));
            if (known.size() != 1) return null;
            return List.of(task(
                    desiredPrincipal,
                    known.getFirst(),
                    "UPSERT_PRINCIPAL",
                    reenablePayload(desiredPrincipal, known.getFirst())));
        }

        var profile = profiles.resolve(tenant, desiredPrincipal.identityId());
        if (profile.status() != PrincipalProvisioningProfileQuery.Status.AVAILABLE) {
            return null;
        }
        var candidates = provisioning.creationTargets(
                tenant, desiredPrincipal.applicationTargetId());
        if (candidates.size() != 1) return null;
        var target = candidates.getFirst();
        String template = target.userNameTemplate();
        if (template == null || template.isBlank() || !template.contains("{identityId}")) {
            return null;
        }
        String userName = template.replace(
                "{identityId}", desiredPrincipal.identityId().toString());
        Map<String,Object> payload = new LinkedHashMap<>();
        payload.put("identityId", desiredPrincipal.identityId().toString());
        payload.put("applicationTargetId",
                desiredPrincipal.applicationTargetId().toString());
        payload.put("userName", userName);
        payload.put("displayName", profile.displayName());
        payload.put("externalId", desiredPrincipal.identityId().toString());
        payload.put("active", true);
        return List.of(task(
                desiredPrincipal, target, "UPSERT_PRINCIPAL", payload));
    }

    private List<PrincipalProvisioningRepository.TaskSpec> absentTasks(
            TenantContext tenant,
            DesiredPrincipalState desiredPrincipal) {
        List<PrincipalProvisioningRepository.TaskSpec> tasks = new ArrayList<>();
        for (var principal : principals.forIdentityTarget(
                tenant,
                desiredPrincipal.identityId(),
                desiredPrincipal.applicationTargetId())) {
            if (principal.status() != PrincipalTechnicalReferenceQuery.Status.ACTIVE) {
                continue;
            }
            for (var target : uniqueTargets(provisioning.existingTargets(
                    tenant,
                    desiredPrincipal.applicationTargetId(),
                    desiredPrincipal.id(),
                    principal.nativePrincipalKey()))) {
                Map<String,Object> payload = new LinkedHashMap<>();
                payload.put("identityId", desiredPrincipal.identityId().toString());
                payload.put("applicationTargetId",
                        desiredPrincipal.applicationTargetId().toString());
                payload.put("providerStableId", principal.nativePrincipalKey());
                if (target.providerVersion() != null) {
                    payload.put("providerVersion", target.providerVersion());
                }
                tasks.add(task(
                        desiredPrincipal, target, "DISABLE_PRINCIPAL", payload));
            }
        }
        return List.copyOf(tasks);
    }

    private static Map<String,Object> reenablePayload(
            DesiredPrincipalState desiredPrincipal,
            PrincipalProvisioningRepository.TechnicalPrincipalTarget target) {
        Map<String,Object> payload = new LinkedHashMap<>();
        payload.put("identityId", desiredPrincipal.identityId().toString());
        payload.put("applicationTargetId",
                desiredPrincipal.applicationTargetId().toString());
        payload.put("providerStableId", target.providerPrincipalId());
        if (target.providerVersion() != null) {
            payload.put("providerVersion", target.providerVersion());
        }
        payload.put("active", true);
        return payload;
    }

    private static PrincipalProvisioningRepository.TaskSpec task(
            DesiredPrincipalState desiredPrincipal,
            PrincipalProvisioningRepository.TechnicalPrincipalTarget target,
            String operation,
            Map<String,Object> payload) {
        String targetKey = target.connectorBindingId()
                + "|" + String.valueOf(target.providerPrincipalId());
        String fingerprint = RequestFingerprint.sha256(
                (desiredPrincipal.id() + "|" + desiredPrincipal.desiredRevision()
                        + "|" + operation + "|" + targetKey)
                        .getBytes(StandardCharsets.UTF_8))
                .value();
        String taskKey = "dp:" + desiredPrincipal.id()
                + ":r" + desiredPrincipal.desiredRevision()
                + ":" + operation + ":" + fingerprint.substring(
                        Math.max(0, fingerprint.length() - 32));
        return new PrincipalProvisioningRepository.TaskSpec(
                taskKey,
                target.connectorBindingId(),
                target.contractId(),
                target.contractVersion(),
                operation,
                payload);
    }

    private static List<PrincipalProvisioningRepository.TechnicalPrincipalTarget>
            uniqueTargets(
                    List<PrincipalProvisioningRepository.TechnicalPrincipalTarget> values) {
        LinkedHashMap<String,PrincipalProvisioningRepository.TechnicalPrincipalTarget>
                unique = new LinkedHashMap<>();
        for (var value : values) {
            unique.put(
                    value.connectorBindingId() + "|"
                            + String.valueOf(value.providerPrincipalId()),
                    value);
        }
        return List.copyOf(unique.values());
    }

    public enum PlanResult {
        PLANNED,
        NOOP,
        STALE_NOOP,
        RETRY
    }
}
