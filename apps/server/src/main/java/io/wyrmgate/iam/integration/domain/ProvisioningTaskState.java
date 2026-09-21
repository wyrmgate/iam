package io.wyrmgate.iam.integration.domain;

public enum ProvisioningTaskState {
    READY,
    RUNNING,
    SUCCEEDED,
    FAILED_RETRYABLE,
    FAILED_FINAL,
    SUPERSEDED,
    SKIPPED,
    BLOCKED
}
