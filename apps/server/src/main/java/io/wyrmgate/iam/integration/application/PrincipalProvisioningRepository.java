package io.wyrmgate.iam.integration.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface PrincipalProvisioningRepository {

    List<TechnicalPrincipalTarget> creationTargets(
            TenantContext tenant, UUID applicationTargetId);

    List<TechnicalPrincipalTarget> existingTargets(
            TenantContext tenant,
            UUID applicationTargetId,
            UUID desiredPrincipalId,
            String providerPrincipalId);

    boolean createPlan(
            TenantContext tenant,
            String planKey,
            UUID desiredPrincipalId,
            long desiredRevision,
            List<TaskSpec> tasks,
            UUID correlationId,
            UUID causationId,
            Instant now);

    record TechnicalPrincipalTarget(
            UUID connectorBindingId,
            String contractId,
            int contractVersion,
            String providerPrincipalId,
            String providerVersion,
            String userNameTemplate) {}

    record TaskSpec(
            String taskKey,
            UUID connectorBindingId,
            String contractId,
            int contractVersion,
            String operationType,
            Map<String,Object> payload) {
        public TaskSpec {
            payload = Map.copyOf(payload);
        }
    }
}
