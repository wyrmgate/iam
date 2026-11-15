package io.wyrmgate.iam.integration.application.federation;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Integration-owned persistence port; links preserve exact historical interpretation. */
public interface FederationTrustRepository extends FederationTrustQuery {
    record TrustRecord(Trust trust,OidcTrustSettings settings,String lifecycleState,Instant createdAt,Instant updatedAt,Instant retiredAt,UUID clientRegistrationId) {}
    record LinkRecord(SubjectLink link,String lifecycleState,Instant createdAt,Instant updatedAt,Instant retiredAt) {}
    Optional<TrustRecord> activeForClient(TenantContext tenant,UUID clientRegistrationId);
    TrustRecord bindClient(TenantContext tenant,UUID id,long revision,UUID clientRegistrationId,Instant now);
    Optional<TrustRecord> findTrust(TenantContext tenant,UUID id);
    Optional<LinkRecord> findLink(TenantContext tenant,UUID id);
    Optional<TrustRecord> lockActiveTrust(TenantContext tenant,UUID id);
    TrustRecord insertTrust(TenantContext tenant,Trust trust,OidcTrustSettings settings,Instant now);
    LinkRecord insertLink(TenantContext tenant,SubjectLink link,Instant now);
    TrustRecord retireTrust(TenantContext tenant,UUID id,long revision,Instant now);
    LinkRecord retireLink(TenantContext tenant,UUID id,long revision,Instant now);
}
