package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;

/** Governance-owned checkpoint; the caller holds its local transaction for a page. */
public interface GovernanceObservationEvaluationRepository {
    Checkpoint lockOrStart(TenantContext tenant, UUID binding, long sourceGeneration, Instant now);
    Checkpoint advance(TenantContext tenant, UUID binding, Checkpoint current,
                       Phase phase, String afterKey, Instant now);
    record DueEvaluation(TenantContext tenant,UUID binding,Checkpoint checkpoint) {}
    java.util.List<DueEvaluation> lockCompletedDue(Instant before,int limit);
    enum Phase { ENTITLEMENTS, GRANTS, RESOLUTION, COMPLETED }
    record Checkpoint(long sourceGeneration, Phase phase, String afterKey, long revision) {}
}
