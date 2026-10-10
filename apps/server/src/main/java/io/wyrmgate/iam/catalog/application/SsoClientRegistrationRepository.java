package io.wyrmgate.iam.catalog.application;

import io.wyrmgate.iam.catalog.application.CatalogQueryModels.PagePosition;
import io.wyrmgate.iam.catalog.application.SsoClientProtocolQuery.ResolvedClient;
import io.wyrmgate.iam.catalog.domain.SsoClientRegistration;
import io.wyrmgate.iam.catalog.domain.SsoClientScope;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Catalog-owned persistence port for governed SSO relying-party registration state. */
public interface SsoClientRegistrationRepository {

    void insert(TenantContext tenant, SsoClientRegistration registration);

    Optional<SsoClientRegistration> find(TenantContext tenant, UUID registrationId);

    Optional<SsoClientRegistration> findActiveByClientId(TenantContext tenant, String clientId);

    Optional<ResolvedClient> findActiveProtocolClient(String clientId);

    List<SsoClientRegistration> findPage(
            TenantContext tenant, UUID applicationId, PagePosition after, int limit);

    SsoClientRegistration replaceConfiguration(
            TenantContext tenant,
            UUID registrationId,
            Set<String> redirectUris,
            Set<SsoClientScope> allowedScopes,
            boolean requiresGovernedAccess,
            long expectedRevision,
            Instant now);

    SsoClientRegistration retire(
            TenantContext tenant,
            UUID registrationId,
            long expectedRevision,
            Instant now);
}
