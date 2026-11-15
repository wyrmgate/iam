package io.wyrmgate.iam.integration.application.federation;

import io.wyrmgate.iam.integration.application.IntegrationAdministrationException;
import io.wyrmgate.iam.identity.application.IdentityAuthenticationQuery;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import io.wyrmgate.iam.tenancy.application.TenantOverviewQuery;
import io.wyrmgate.iam.tenancy.domain.TenantLifecycleState;
import java.time.Instant;
import java.util.UUID;

/** Explicit trust creation/retirement; no auto account adoption or provider-claim authority. */
public final class FederationTrustCommands {
    private final FederationTrustRepository repository;
    private final IdentityAuthenticationQuery identities;
    private final TenantOverviewQuery tenants;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;
    public FederationTrustCommands(FederationTrustRepository repository,IdentityAuthenticationQuery identities,
            TenantOverviewQuery tenants,IdGenerator ids,TransactionExecutor transactions) {
        this.repository=repository;this.identities=identities;this.tenants=tenants;this.ids=ids;this.transactions=transactions;
    }
    public FederationTrustRepository.TrustRecord createOidc(TenantContext tenant,String issuer,String audience,
            OidcTrustSettings settings,Instant now) {
        settings.validateIssuer(issuer);
        var trust=new FederationTrustQuery.Trust(tenant.tenantId(),ids.nextId(),1,FederationTrustQuery.Protocol.OIDC,
                OidcTrustSettings.PROVIDER_KEY,issuer,audience);
        return transactions.required(()->{
            requireActiveTenant(tenant);
            return repository.insertTrust(tenant,trust,settings,now);
        });
    }
    public FederationTrustRepository.LinkRecord link(TenantContext tenant,UUID trustId,String exactSubject,UUID principalId,Instant now) {
        return transactions.required(()->{
            requireActiveTenant(tenant);
            repository.lockActiveTrust(tenant,trustId).orElseThrow(()->new IntegrationAdministrationException("not_found","Active federation trust not found."));
            if(identities.eligibleSubject(tenant,principalId).filter(s->s.principalId().equals(principalId)).isEmpty())
                throw new IntegrationAdministrationException("principal_not_found","Eligible same-tenant Principal not found.");
            var link=new FederationTrustQuery.SubjectLink(tenant.tenantId(),ids.nextId(),1,trustId,exactSubject,principalId);
            return repository.insertLink(tenant,link,now);
        });
    }
    public FederationTrustRepository.TrustRecord retireTrust(TenantContext tenant,UUID trustId,long revision,Instant now) {
        return transactions.required(()->repository.retireTrust(tenant,trustId,revision,now));
    }
    public FederationTrustRepository.LinkRecord retireLink(TenantContext tenant,UUID linkId,long revision,Instant now) {
        return transactions.required(()->repository.retireLink(tenant,linkId,revision,now));
    }
    private void requireActiveTenant(TenantContext tenant) {
        if(tenants.find(tenant.tenantId()).filter(t->t.lifecycleState()==TenantLifecycleState.ACTIVE).isEmpty())
            throw new IntegrationAdministrationException("tenant_unavailable","Active Tenant required.");
    }
}
