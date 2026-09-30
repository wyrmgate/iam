package io.wyrmgate.iam.audit.domain;

/** Normalized outcome of one audited semantic action. */
public enum AuditOutcome {
    SUCCESS,
    DENIED,
    FAILURE
}
