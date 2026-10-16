package io.wyrmgate.iam.catalog.application;

import io.wyrmgate.iam.administration.application.PlatformOwnerAuthorityQuery;
import io.wyrmgate.iam.catalog.domain.ApplicationTenancyMode;
import io.wyrmgate.iam.catalog.domain.CatalogLifecycleState;
import io.wyrmgate.iam.catalog.domain.PlatformApplicationAdmissionMode;
import io.wyrmgate.iam.catalog.domain.PlatformApplicationBinding;
import io.wyrmgate.iam.catalog.domain.PlatformApplicationOffering;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Authoritative Catalog commands for platform-owned application offerings and tenant bindings. */
public final class PlatformApplicationService {

    private final PlatformApplicationRepository platformApplications;
    private final CatalogRepository tenantCatalog;
    private final PlatformOwnerAuthorityQuery platformOwners;
    private final PlatformApplicationAdmissionPolicyQuery governedAdmission;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;

    public PlatformApplicationService(
            PlatformApplicationRepository platformApplications,
            CatalogRepository tenantCatalog,
            PlatformOwnerAuthorityQuery platformOwners,
            PlatformApplicationAdmissionPolicyQuery governedAdmission,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this.platformApplications = Objects.requireNonNull(platformApplications, "platformApplications");
        this.tenantCatalog = Objects.requireNonNull(tenantCatalog, "tenantCatalog");
        this.platformOwners = Objects.requireNonNull(platformOwners, "platformOwners");
        this.governedAdmission = Objects.requireNonNull(governedAdmission, "governedAdmission");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public PlatformApplicationOffering createOffering(
            TenantContext actorTenant,
            UUID actorIdentityId,
            String code,
            String name,
            ApplicationTenancyMode tenancyMode,
            PlatformApplicationAdmissionMode admissionMode,
            String governedPolicyRef,
            Instant now) {
        requirePlatformOwner(actorTenant, actorIdentityId, now);
        PlatformApplicationOffering offering = new PlatformApplicationOffering(
                ids.nextId(),
                code,
                name,
                tenancyMode,
                admissionMode,
                governedPolicyRef,
                CatalogLifecycleState.ACTIVE,
                1,
                now,
                now);
        return transactions.required(() -> {
            platformApplications.insertOffering(offering);
            return platformApplications.findOffering(offering.id())
                    .orElseThrow(() -> new IllegalStateException("created platform application could not be reloaded"));
        });
    }

    public void admitSelectedTenant(
            TenantContext actorTenant,
            UUID actorIdentityId,
            UUID offeringId,
            TenantContext admittedTenant,
            Instant now) {
        requirePlatformOwner(actorTenant, actorIdentityId, now);
        Objects.requireNonNull(offeringId, "offeringId");
        Objects.requireNonNull(admittedTenant, "admittedTenant");
        PlatformApplicationOffering offering = requireActiveOffering(offeringId);
        if (offering.admissionMode() != PlatformApplicationAdmissionMode.SELECTED_TENANTS) {
            throw new IllegalStateException("offering does not use SELECTED_TENANTS admission");
        }
        transactions.required(() -> {
            platformApplications.admitSelectedTenant(offeringId, admittedTenant, now);
            return null;
        });
    }

    public PlatformApplicationBinding bindTenantApplication(
            TenantContext actorTenant,
            UUID actorIdentityId,
            UUID offeringId,
            TenantContext tenant,
            UUID tenantApplicationId,
            Instant now) {
        requirePlatformOwner(actorTenant, actorIdentityId, now);
        Objects.requireNonNull(offeringId, "offeringId");
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(tenantApplicationId, "tenantApplicationId");
        Objects.requireNonNull(now, "now");

        return transactions.required(() -> {
            PlatformApplicationOffering offering = requireActiveOffering(offeringId);
            if (!isTenantAdmitted(offering, tenant, now)) {
                throw new IllegalStateException("tenant is not admitted to platform application offering");
            }
            tenantCatalog.findApplication(tenant, tenantApplicationId)
                    .filter(application -> application.lifecycleState() == CatalogLifecycleState.ACTIVE)
                    .orElseThrow(() -> new IllegalArgumentException("active tenant Application does not exist"));

            PlatformApplicationBinding existing = platformApplications.findBinding(offeringId, tenant).orElse(null);
            if (existing != null) {
                if (existing.applicationId().equals(tenantApplicationId)
                        && existing.lifecycleState() == CatalogLifecycleState.ACTIVE) {
                    return existing;
                }
                throw new IllegalStateException("platform application already has a different tenant binding");
            }

            PlatformApplicationBinding binding = new PlatformApplicationBinding(
                    ids.nextId(),
                    offeringId,
                    tenant.tenantId(),
                    tenantApplicationId,
                    CatalogLifecycleState.ACTIVE,
                    1,
                    now,
                    now);
            platformApplications.insertBinding(binding);
            return platformApplications.findBinding(offeringId, tenant)
                    .orElseThrow(() -> new IllegalStateException("created platform application binding could not be reloaded"));
        });
    }

    private boolean isTenantAdmitted(
            PlatformApplicationOffering offering,
            TenantContext tenant,
            Instant now) {
        return switch (offering.admissionMode()) {
            case ALL_TENANTS -> true;
            case SELECTED_TENANTS -> platformApplications.isSelectedTenantAdmitted(offering.id(), tenant);
            case GOVERNED_POLICY -> governedAdmission.isTenantAdmitted(offering, tenant, now);
        };
    }

    private PlatformApplicationOffering requireActiveOffering(UUID offeringId) {
        return platformApplications.findOffering(offeringId)
                .filter(offering -> offering.lifecycleState() == CatalogLifecycleState.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException("active platform application offering does not exist"));
    }

    private void requirePlatformOwner(
            TenantContext actorTenant,
            UUID actorIdentityId,
            Instant now) {
        Objects.requireNonNull(actorTenant, "actorTenant");
        Objects.requireNonNull(actorIdentityId, "actorIdentityId");
        Objects.requireNonNull(now, "now");
        if (!platformOwners.isViablePlatformOwner(actorTenant, actorIdentityId, now)) {
            throw new SecurityException("viable PLATFORM_OWNER authority is required");
        }
    }
}
