package io.wyrmgate.iam.credential.application;

import io.wyrmgate.iam.credential.domain.CredentialModels.Credential;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;

public interface CredentialBoundaryScheduler {

    void scheduleBoundaries(
            TenantContext tenant,
            Credential credential,
            Instant now);
}
