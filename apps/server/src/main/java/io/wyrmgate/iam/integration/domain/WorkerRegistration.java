package io.wyrmgate.iam.integration.domain;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;

public record WorkerRegistration(
        UUID id,
        TenantContext tenant,
        WorkerExternalSubject externalSubject,
        boolean enabled,
        int protocolMajorMin,
        int protocolMajorMax) {

    public WorkerRegistration {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(externalSubject, "externalSubject");
        if (protocolMajorMin < 1 || protocolMajorMax < protocolMajorMin) {
            throw new IllegalArgumentException("invalid protocol major range");
        }
    }

    public boolean supportsProtocol(int major) {
        return enabled && major >= protocolMajorMin && major <= protocolMajorMax;
    }
}
