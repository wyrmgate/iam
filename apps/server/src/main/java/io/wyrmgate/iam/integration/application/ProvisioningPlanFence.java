package io.wyrmgate.iam.integration.application;
import io.wyrmgate.iam.integration.application.ConnectorWorkRepository.ProvisioningCandidate;
import java.util.UUID;

public interface ProvisioningPlanFence {
    boolean currentPlan(ProvisioningCandidate candidate,UUID applicationTargetId);
    boolean removalMappingSafe(ProvisioningCandidate candidate,UUID principalId,UUID entitlementId);
    boolean replacementSucceeded(ProvisioningCandidate candidate,GrantProvisioningRepository.TechnicalGrantTarget replacement);
}
