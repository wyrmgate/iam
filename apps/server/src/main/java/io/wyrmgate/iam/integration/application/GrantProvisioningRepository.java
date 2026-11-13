package io.wyrmgate.iam.integration.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;

public interface GrantProvisioningRepository {

    List<TechnicalGrantTarget> addTargets(
            TenantContext tenant,
            UUID applicationTargetId,
            UUID entitlementId,
            String providerPrincipalId);

    /** At most pageSize + 1 distinct technical routes in route-key order. */
    List<CleanupTarget> cleanupTargetsPage(
            TenantContext tenant, UUID desiredGrantId, UUID applicationTargetId,
            UUID entitlementId, UUID observedAndTechnicalPrincipalId,
            CleanupCursor after, int pageSize);

    boolean hasUninterpretablePriorEvidence(
            TenantContext tenant, UUID desiredGrantId, UUID entitlementId);

    Optional<String> taskState(TenantContext tenant, String taskKey);

    record CleanupCursor(UUID connectorBindingId, String providerEntitlementId,
                         String providerPrincipalId) {
        public CleanupCursor {
            Objects.requireNonNull(connectorBindingId, "connectorBindingId");
            Objects.requireNonNull(providerEntitlementId, "providerEntitlementId");
            Objects.requireNonNull(providerPrincipalId, "providerPrincipalId");
        }

        public static CleanupCursor from(TechnicalGrantTarget target) {
            return new CleanupCursor(target.connectorBindingId(), target.providerEntitlementId(),
                    target.providerPrincipalId());
        }
    }

    /** Null governed Principal denotes conflicting/malformed evidence, never a guessed mapping. */
    record CleanupTarget(TechnicalGrantTarget target, boolean includesObservation) {}

    boolean createPlan(
            TenantContext tenant,
            String planKey,
            UUID desiredGrantId,
            long desiredRevision,
            List<TaskSpec> tasks,
            UUID correlationId,
            UUID causationId,
            Instant now);

    record TechnicalGrantTarget(
            UUID connectorBindingId,
            String contractId,
            int contractVersion,
            String providerEntitlementId,
            String providerEntitlementVersion,
            String providerPrincipalId,
            UUID principalId) {

        public TechnicalGrantTarget withPrincipalId(UUID value) {
            return new TechnicalGrantTarget(
                    connectorBindingId,
                    contractId,
                    contractVersion,
                    providerEntitlementId,
                    providerEntitlementVersion,
                    providerPrincipalId,
                    value);
        }
    }

    record TaskSpec(
            String taskKey,
            UUID connectorBindingId,
            String contractId,
            int contractVersion,
            String operationType,
            Map<String,Object> payload,
            String dependsOnTaskKey) {
        public TaskSpec {
            payload = Map.copyOf(payload);
        }
    }
}
