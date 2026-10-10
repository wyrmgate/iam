package io.wyrmgate.iam.catalog.application;

import io.wyrmgate.iam.catalog.application.CatalogQueryModels.PagePosition;
import io.wyrmgate.iam.catalog.domain.CatalogLifecycleState;
import io.wyrmgate.iam.catalog.domain.SsoClientLifecycleState;
import io.wyrmgate.iam.catalog.domain.SsoClientRegistration;
import io.wyrmgate.iam.catalog.domain.SsoClientScope;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Catalog-owned semantic service for governed public OIDC client registrations. */
public final class SsoClientRegistrationService implements SsoClientProtocolQuery {

    private final CatalogRepository catalog;
    private final SsoClientRegistrationRepository registrations;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;

    public SsoClientRegistrationService(
            CatalogRepository catalog,
            SsoClientRegistrationRepository registrations,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.registrations = Objects.requireNonNull(registrations, "registrations");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public SsoClientRegistration create(
            TenantContext tenant,
            UUID applicationId,
            Set<String> redirectUris,
            Set<SsoClientScope> allowedScopes,
            boolean requiresGovernedAccess,
            Instant now) {
        return transactions.required(() -> {
            var application = catalog.findApplication(tenant, applicationId)
                    .orElseThrow(() -> new IllegalArgumentException("application does not exist"));
            if (application.lifecycleState() != CatalogLifecycleState.ACTIVE) {
                throw new IllegalArgumentException("application is retired");
            }
            UUID id = ids.nextId();
            String clientId = "wc_" + ids.nextId().toString().replace("-", "");
            SsoClientRegistration registration = new SsoClientRegistration(
                    id,
                    applicationId,
                    clientId,
                    redirectUris,
                    allowedScopes,
                    requiresGovernedAccess,
                    SsoClientLifecycleState.ACTIVE,
                    1,
                    now,
                    now);
            registrations.insert(tenant, registration);
            return registration;
        });
    }

    public SsoClientRegistration replaceConfiguration(
            TenantContext tenant,
            UUID registrationId,
            Set<String> redirectUris,
            Set<SsoClientScope> allowedScopes,
            boolean requiresGovernedAccess,
            long expectedRevision,
            Instant now) {
        SsoClientRegistration current = registrations.find(tenant, registrationId)
                .orElseThrow(() -> new IllegalArgumentException("SSO client registration does not exist"));
        new SsoClientRegistration(
                current.id(), current.applicationId(), current.clientId(), redirectUris, allowedScopes,
                requiresGovernedAccess, current.lifecycleState(), current.revision(), current.createdAt(), now);
        return transactions.required(() -> registrations.replaceConfiguration(
                tenant,
                registrationId,
                redirectUris,
                allowedScopes,
                requiresGovernedAccess,
                expectedRevision,
                now));
    }

    public SsoClientRegistration retire(
            TenantContext tenant,
            UUID registrationId,
            long expectedRevision,
            Instant now) {
        return transactions.required(() -> registrations.retire(
                tenant, registrationId, expectedRevision, now));
    }

    public Optional<SsoClientRegistration> find(TenantContext tenant, UUID registrationId) {
        return registrations.find(tenant, registrationId);
    }

    /** Tenant-bound protocol query used after the tenant has already been resolved server-side. */
    public Optional<SsoClientRegistration> findActiveByClientId(TenantContext tenant, String clientId) {
        return registrations.findActiveByClientId(tenant, clientId);
    }

    /**
     * Public protocol routing query. A retired/missing parent Application invalidates the client even
     * if an older registration row remains ACTIVE.
     */
    @Override
    public Optional<ResolvedClient> resolveActive(String clientId) {
        if (clientId == null || clientId.isBlank()) return Optional.empty();
        return registrations.findActiveProtocolClient(clientId)
                .filter(resolved -> catalog.findApplication(
                                resolved.tenant(), resolved.registration().applicationId())
                        .filter(application -> application.lifecycleState() == CatalogLifecycleState.ACTIVE)
                        .isPresent());
    }

    public Page list(
            TenantContext tenant,
            UUID applicationId,
            PagePosition after,
            int limit) {
        List<SsoClientRegistration> fetched = registrations.findPage(tenant, applicationId, after, limit + 1);
        boolean hasMore = fetched.size() > limit;
        List<SsoClientRegistration> items = hasMore ? fetched.subList(0, limit) : fetched;
        PagePosition next = hasMore && !items.isEmpty()
                ? new PagePosition(items.get(items.size() - 1).createdAt(), items.get(items.size() - 1).id())
                : null;
        return new Page(items, next);
    }

    public record Page(List<SsoClientRegistration> items, PagePosition nextPosition) {
        public Page {
            items = List.copyOf(items);
        }
    }
}
