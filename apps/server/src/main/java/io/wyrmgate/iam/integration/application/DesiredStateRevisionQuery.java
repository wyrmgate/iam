package io.wyrmgate.iam.integration.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.OptionalLong;
import java.util.UUID;

/** Semantic cross-capability freshness query used before provider-side provisioning execution. */
public interface DesiredStateRevisionQuery {
    OptionalLong currentRevision(TenantContext tenant, String subjectKind, UUID subjectId);

    static DesiredStateRevisionQuery unavailable() {
        return (tenant, subjectKind, subjectId) -> OptionalLong.empty();
    }
}
