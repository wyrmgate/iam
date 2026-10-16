package io.wyrmgate.iam.catalog.application;

import io.wyrmgate.iam.catalog.domain.PlatformApplicationBinding;
import io.wyrmgate.iam.catalog.domain.PlatformApplicationOffering;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Catalog-owned persistence port for platform application offerings, admissions and bindings. */
public interface PlatformApplicationRepository {

    void insertOffering(PlatformApplicationOffering offering);

    Optional<PlatformApplicationOffering> findOffering(UUID offeringId);

    void admitSelectedTenant(UUID offeringId, TenantContext tenant, Instant now);

    boolean isSelectedTenantAdmitted(UUID offeringId, TenantContext tenant);

    void insertBinding(PlatformApplicationBinding binding);

    Optional<PlatformApplicationBinding> findBinding(UUID offeringId, TenantContext tenant);
}
