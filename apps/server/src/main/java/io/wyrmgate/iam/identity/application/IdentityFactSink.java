package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.identity.domain.Identity;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.UUID;

/** Port for recording semantic Identity facts atomically with authoritative mutations. */
public interface IdentityFactSink {

    void identityCreated(
            TenantContext tenant,
            Identity identity,
            UUID correlationId,
            UUID causationId);

    void displayNameChanged(
            TenantContext tenant,
            Identity identity,
            UUID correlationId,
            UUID causationId);
}
