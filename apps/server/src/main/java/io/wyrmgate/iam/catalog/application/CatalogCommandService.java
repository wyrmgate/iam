package io.wyrmgate.iam.catalog.application;

import io.wyrmgate.iam.catalog.domain.Application;
import io.wyrmgate.iam.catalog.domain.ApplicationTarget;
import io.wyrmgate.iam.catalog.domain.CatalogLifecycleState;
import io.wyrmgate.iam.catalog.domain.Entitlement;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class CatalogCommandService {

    private final CatalogRepository repository;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;

    public CatalogCommandService(
            CatalogRepository repository,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public Application createApplication(
            TenantContext tenant, String code, String name, Instant now) {
        return transactions.required(() -> {
            Application application = new Application(
                    ids.nextId(), code, name, CatalogLifecycleState.ACTIVE, 1, now, now);
            repository.insertApplication(tenant, application);
            return application;
        });
    }

    public Application renameApplication(
            TenantContext tenant,
            UUID applicationId,
            String name,
            long expectedRevision,
            Instant now) {
        return transactions.required(() ->
                repository.updateApplicationName(
                        tenant, applicationId, name, expectedRevision, now));
    }

    public Application retireApplication(
            TenantContext tenant,
            UUID applicationId,
            long expectedRevision,
            Instant now) {
        return transactions.required(() ->
                repository.retireApplication(
                        tenant, applicationId, expectedRevision, now));
    }

    public ApplicationTarget createTarget(
            TenantContext tenant,
            UUID applicationId,
            String code,
            Instant now) {
        return transactions.required(() -> {
            Application application = repository.findApplication(tenant, applicationId)
                    .orElseThrow(() -> new IllegalArgumentException("application does not exist"));
            if (application.lifecycleState() != CatalogLifecycleState.ACTIVE) {
                throw new IllegalArgumentException("application is retired");
            }
            ApplicationTarget target = new ApplicationTarget(
                    ids.nextId(), applicationId, code,
                    CatalogLifecycleState.ACTIVE, 1, now, now);
            repository.insertTarget(tenant, target);
            return target;
        });
    }

    public ApplicationTarget retireTarget(
            TenantContext tenant,
            UUID targetId,
            long expectedRevision,
            Instant now) {
        return transactions.required(() ->
                repository.retireTarget(tenant, targetId, expectedRevision, now));
    }

    public Entitlement createEntitlement(
            TenantContext tenant,
            UUID applicationId,
            UUID applicationTargetId,
            String code,
            String nativeKey,
            String entitlementType,
            Instant now) {
        return transactions.required(() -> {
            Application application = repository.findApplication(tenant, applicationId)
                    .orElseThrow(() -> new IllegalArgumentException("application does not exist"));
            if (application.lifecycleState() != CatalogLifecycleState.ACTIVE) {
                throw new IllegalArgumentException("application is retired");
            }
            if (applicationTargetId != null) {
                ApplicationTarget target = repository.findTarget(tenant, applicationTargetId)
                        .orElseThrow(() -> new IllegalArgumentException("application target does not exist"));
                if (!target.applicationId().equals(applicationId)) {
                    throw new IllegalArgumentException("application target belongs to another application");
                }
                if (target.lifecycleState() != CatalogLifecycleState.ACTIVE) {
                    throw new IllegalArgumentException("application target is retired");
                }
            }
            Entitlement entitlement = new Entitlement(
                    ids.nextId(), applicationId, applicationTargetId, code, nativeKey,
                    entitlementType, CatalogLifecycleState.ACTIVE, 1, now, now);
            repository.insertEntitlement(tenant, entitlement);
            return entitlement;
        });
    }

    public Entitlement retireEntitlement(
            TenantContext tenant,
            UUID entitlementId,
            long expectedRevision,
            Instant now) {
        return transactions.required(() ->
                repository.retireEntitlement(
                        tenant, entitlementId, expectedRevision, now));
    }
}
