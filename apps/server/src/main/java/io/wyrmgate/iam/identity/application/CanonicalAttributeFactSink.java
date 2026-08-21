package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.identity.domain.CanonicalAttributeOverride;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeState;
import io.wyrmgate.iam.identity.domain.CanonicalSchemaVersion;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.UUID;

/** Data-minimized internal semantic facts for canonical attribute configuration/resolution. */
public interface CanonicalAttributeFactSink {
    void schemaActivated(TenantContext tenant, CanonicalSchemaVersion schema, UUID correlationId, UUID causationId);
    void stateChanged(TenantContext tenant, CanonicalAttributeState state, String canonicalKey, UUID correlationId, UUID causationId);
    void overrideApplied(TenantContext tenant, CanonicalAttributeOverride override, String canonicalKey);
}
