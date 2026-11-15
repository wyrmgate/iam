package io.wyrmgate.iam.governance.application;

import java.util.Objects;
import java.util.UUID;

/** Immutable material context of one positive request evaluation, not a grant. */
public record RequestAdmissionContext(long identityEpoch, UUID policyVersionId,
                                      String targetFingerprint, String riskFingerprint,
                                      UUID policyEvaluationId) {
    public RequestAdmissionContext {
        if (identityEpoch < 1) throw new IllegalArgumentException("positive Identity epoch required");
        Objects.requireNonNull(policyVersionId, "policyVersionId");
        Objects.requireNonNull(policyEvaluationId, "policyEvaluationId");
        requireFingerprint(targetFingerprint);
        requireFingerprint(riskFingerprint);
    }

    public boolean sameMaterial(RequestAdmissionContext other) {
        return other != null && identityEpoch == other.identityEpoch
                && policyVersionId.equals(other.policyVersionId)
                && targetFingerprint.equals(other.targetFingerprint)
                && riskFingerprint.equals(other.riskFingerprint);
    }

    private static void requireFingerprint(String value) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}"))
            throw new IllegalArgumentException("canonical SHA-256 fingerprint required");
    }
}
