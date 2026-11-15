package io.wyrmgate.iam.integration.application;

import io.wyrmgate.iam.integration.application.ConnectorWorkRepository.ProvisioningCandidate;

/** Semantic pre-provider admission, not an access authority. */
@FunctionalInterface
public interface ProvisioningAdmission {
    enum Decision { ADMIT, RETRY, BLOCKED }
    Decision check(ProvisioningCandidate candidate);
}
