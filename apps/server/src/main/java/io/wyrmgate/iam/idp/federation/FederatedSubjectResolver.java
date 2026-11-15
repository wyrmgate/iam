package io.wyrmgate.iam.idp.federation;

import io.wyrmgate.iam.integration.application.federation.FederationTrustQuery;
import io.wyrmgate.iam.integration.application.federation.FederationTrustQuery.VerificationContext;
import io.wyrmgate.iam.integration.application.federation.VerifiedFederatedSubject;
import io.wyrmgate.iam.identity.application.IdentityAuthenticationQuery;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import io.wyrmgate.iam.tenancy.application.TenantOverviewQuery;
import io.wyrmgate.iam.tenancy.domain.TenantLifecycleState;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Current-state composition after the adapter consumes its durable callback fence. All unavailable mandatory reads fail closed.
 */
public final class FederatedSubjectResolver {
    private final FederationTrustQuery federation;
    private final IdentityAuthenticationQuery identities;
    private final TenantOverviewQuery tenants;

    public FederatedSubjectResolver(FederationTrustQuery federation, IdentityAuthenticationQuery identities,
                                    TenantOverviewQuery tenants) {
        this.federation = Objects.requireNonNull(federation);
        this.identities = Objects.requireNonNull(identities);
        this.tenants = Objects.requireNonNull(tenants);
    }

    public Optional<FederatedAuthenticationAnchor> resolve(VerificationContext expected,
                                                           VerifiedFederatedSubject proof, Instant now) {
        if (expected == null || proof == null || now == null || !expected.equals(proof.context())
                || !now.isBefore(expected.expiresAt()) || !now.isBefore(proof.expiresAt())
                || proof.authenticatedAt().isAfter(now)) return Optional.empty();
        var trust = expected.trust();
        var tenant = new TenantContext(trust.tenantId());
        try {
            var tenantRevision = activeTenant(tenant);
            if (tenantRevision.isEmpty() || federation.activeTrust(tenant, trust.id()).filter(trust::equals).isEmpty())
                return Optional.empty();
            var link = federation.activeLink(tenant, trust.id(), proof.exactSubject())
                    .filter(l -> l.tenantId().equals(tenant.tenantId()) && l.trustId().equals(trust.id())
                            && l.exactSubject().equals(proof.exactSubject())).orElse(null);
            if (link == null) return Optional.empty();
            return identities.eligibleSubject(tenant, link.principalId())
                    .filter(subject -> subject.principalId().equals(link.principalId()))
                    .map(subject -> new FederatedAuthenticationAnchor(tenant.tenantId(), tenantRevision.orElseThrow(),
                            trust.id(), trust.revision(), link.id(), link.revision(), subject.principalId(),
                            subject.principalRevision(), subject.identityId(), subject.identityRevision(),
                            proof.authenticatedAt(), proof.expiresAt()));
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    public boolean isStillValid(TenantContext tenant, FederatedAuthenticationAnchor anchor, Instant now) {
        if (tenant == null || anchor == null || now == null || !tenant.tenantId().equals(anchor.tenantId())
                || now.isBefore(anchor.authenticatedAt()) || !now.isBefore(anchor.expiresAt())) return false;
        try {
            return activeTenant(tenant).filter(r -> r == anchor.tenantRevision()).isPresent()
                    && federation.activeTrust(tenant, anchor.trustId())
                        .filter(t -> t.tenantId().equals(tenant.tenantId()) && t.id().equals(anchor.trustId())
                                && t.revision() == anchor.trustRevision()).isPresent()
                    && federation.activeLinkById(tenant, anchor.linkId())
                        .filter(l -> l.tenantId().equals(tenant.tenantId()) && l.id().equals(anchor.linkId())
                                && l.trustId().equals(anchor.trustId()) && l.revision() == anchor.linkRevision()
                                && l.principalId().equals(anchor.principalId())).isPresent()
                    && identities.eligibleSubject(tenant, anchor.principalId())
                        .filter(s -> s.principalId().equals(anchor.principalId()) && s.identityId().equals(anchor.identityId())
                                && s.principalRevision() == anchor.principalRevision()
                                && s.identityRevision() == anchor.identityRevision()).isPresent();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private Optional<Long> activeTenant(TenantContext tenant) {
        return tenants.find(tenant.tenantId()).filter(t -> t.lifecycleState() == TenantLifecycleState.ACTIVE)
                .map(TenantOverviewQuery.TenantSummary::revision);
    }
}
