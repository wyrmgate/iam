package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.domain.LifecycleAccessPolicyVersion;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LifecycleAccessPolicyRepository {

    LifecycleAccessPolicyVersion replaceActive(
            TenantContext tenant,
            List<LifecycleAccessPolicyVersion.Rule> rules,
            Instant activatedAt,
            UUID policyVersionId);

    Optional<LifecycleAccessPolicyVersion> findActive(TenantContext tenant);
}
