package io.wyrmgate.iam.integration.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface GrantProvisioningRepository {

    List<TechnicalGrantTarget> addTargets(
            TenantContext tenant,
            UUID applicationTargetId,
            UUID entitlementId,
            String providerPrincipalId);

    List<TechnicalGrantTarget> observedRemoveTargetsByPrincipalIds(
            TenantContext tenant,
            UUID applicationTargetId,
            UUID entitlementId,
            List<UUID> principalIds);

    List<TechnicalGrantTarget> priorSuccessfulAddTargets(
            TenantContext tenant,
            UUID desiredGrantId);

    List<TechnicalGrantTarget> priorSuccessfulTechnicalAddTargets(
            TenantContext tenant,
            UUID applicationTargetId,
            UUID entitlementId,
            UUID principalId);

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
