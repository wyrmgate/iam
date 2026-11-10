package io.wyrmgate.iam.tenancy.application;

import io.wyrmgate.iam.tenancy.domain.Tenant;
import java.util.Optional;
import java.util.UUID;

/** Tenancy-owned persistence port for authoritative Tenant state. */
public interface TenantRepository {

    long count();

    Optional<Tenant> find(UUID tenantId);

    void insert(Tenant tenant);

    Tenant updateLifecycle(Tenant tenant, long expectedRevision);
}
