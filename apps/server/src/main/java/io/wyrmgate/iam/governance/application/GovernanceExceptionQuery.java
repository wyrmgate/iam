package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface GovernanceExceptionQuery {

    Map<UUID,UUID> effectiveExceptionIds(
            TenantContext tenant,
            UUID subjectIdentityId,
            Set<UUID> sodRuleIds,
            Instant at);
}
