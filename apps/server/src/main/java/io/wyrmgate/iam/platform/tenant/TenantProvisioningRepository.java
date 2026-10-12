package io.wyrmgate.iam.platform.tenant;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Platform-owned persistence port for the deployment isolation root. */
public interface TenantProvisioningRepository {

    long countTenants();

    Optional<TenantRecord> find(UUID tenantId);

    void insert(UUID tenantId, String displayName, Instant now);

    record TenantRecord(
            UUID id,
            String displayName,
            long revision,
            Instant createdAt,
            Instant updatedAt) {
    }
}
