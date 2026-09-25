package io.wyrmgate.iam.integration.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;

/** Internal Integration fact for successful IAM-initiated Principal provisioning. */
public interface IntegrationPrincipalProvisioningFactSink {

    String PRINCIPAL_PROVISIONING_SUCCEEDED =
            "integration.principal-provisioning-succeeded";

    void succeeded(
            TenantContext tenant,
            UUID desiredPrincipalId,
            long desiredRevision,
            UUID identityId,
            UUID applicationTargetId,
            String operationType,
            String providerPrincipalId,
            Instant occurredAt,
            UUID correlationId,
            UUID causationId);
}
