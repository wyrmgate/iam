package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.access.application.LifecycleAccessPrivilegeGuard;
import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.governance.domain.GovernancePolicyModels.RiskSeverity;
import io.wyrmgate.iam.governance.domain.GovernancePolicyModels.SoDAction;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable Governance evidence for automatic lifecycle-access privilege evaluation. */
public interface LifecycleAccessEvaluationEvidenceSink {

    void record(
            TenantContext tenant,
            UUID evaluationId,
            UUID identityId,
            UUID lifecycleRuleId,
            UUID governancePolicyVersionId,
            AccessAssignment.TargetKind targetKind,
            UUID targetId,
            LifecycleAccessPrivilegeGuard.Decision decision,
            String code,
            List<ConflictEvidence> conflicts,
            Instant evaluatedAt,
            UUID correlationId,
            UUID causationId);

    record ConflictEvidence(
            UUID sodRuleId,
            RiskSeverity severity,
            SoDAction action,
            UUID governanceExceptionId) {
        public ConflictEvidence {
            Objects.requireNonNull(sodRuleId, "sodRuleId");
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(action, "action");
        }
    }
}
