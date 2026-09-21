package io.wyrmgate.iam.integration.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;

public interface IntegrationAdministrationFactSink {

    void connectorChanged(
            TenantContext tenant, String factType, UUID connectorId, long revision,
            Instant occurredAt, UUID correlationId);

    void bindingChanged(
            TenantContext tenant, String factType, UUID bindingId, long revision,
            Instant occurredAt, UUID correlationId);

    void workerChanged(
            TenantContext tenant, String factType, UUID workerId, long revision,
            Instant occurredAt, UUID correlationId);

    void mappingChanged(
            TenantContext tenant, String factType, UUID mappingId, long revision,
            Instant occurredAt, UUID correlationId);
}
