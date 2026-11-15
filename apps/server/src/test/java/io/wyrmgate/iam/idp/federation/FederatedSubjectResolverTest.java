package io.wyrmgate.iam.idp.federation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import io.wyrmgate.iam.integration.application.federation.FederationTrustQuery;
import io.wyrmgate.iam.integration.application.federation.FederationTrustQuery.*;
import io.wyrmgate.iam.integration.application.federation.VerifiedFederatedSubject;
import io.wyrmgate.iam.identity.application.IdentityAuthenticationQuery;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import io.wyrmgate.iam.tenancy.application.TenantOverviewQuery;
import io.wyrmgate.iam.tenancy.domain.TenantLifecycleState;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FederatedSubjectResolverTest {
    private static final Instant NOW = Instant.parse("2026-11-15T08:00:00Z");
    private final TenantContext tenant = new TenantContext(UUID.randomUUID());
    private final UUID principal = UUID.randomUUID();
    private final UUID identity = UUID.randomUUID();
    private final UUID trustId = UUID.randomUUID();
    private final UUID linkId = UUID.randomUUID();
    private final Trust trust = new Trust(tenant.tenantId(), trustId, 2, Protocol.OIDC,
            "corp", "https://issuer.example", "client-a");
    private final SubjectLink link = new SubjectLink(tenant.tenantId(), linkId, 3, trustId, "stable-subject", principal);
    private final VerificationContext context = new VerificationContext(trust, UUID.randomUUID(), NOW.plusSeconds(60));
    private final VerifiedFederatedSubject proof = new VerifiedFederatedSubject(context, "stable-subject",
            NOW.minusSeconds(5), NOW.plusSeconds(120));
    private final FederationTrustQuery federation = mock(FederationTrustQuery.class);
    private final IdentityAuthenticationQuery identities = mock(IdentityAuthenticationQuery.class);
    private final TenantOverviewQuery tenants = mock(TenantOverviewQuery.class);
    private final FederatedSubjectResolver resolver = new FederatedSubjectResolver(federation, identities, tenants);

    @BeforeEach void currentState() {
        when(tenants.find(tenant.tenantId())).thenReturn(Optional.of(
                new TenantOverviewQuery.TenantSummary(tenant.tenantId(), "Tenant", TenantLifecycleState.ACTIVE, 4, NOW, NOW)));
        when(federation.activeTrust(tenant, trustId)).thenReturn(Optional.of(trust));
        when(federation.activeLink(tenant, trustId, "stable-subject")).thenReturn(Optional.of(link));
        when(federation.activeLinkById(tenant, linkId)).thenReturn(Optional.of(link));
        when(identities.eligibleSubject(tenant, principal)).thenReturn(Optional.of(
                new IdentityAuthenticationQuery.AuthenticationSubject(principal, identity, 5, 6)));
    }

    @Test void exactProofResolvesPinnedAnchorAndNoSyntheticCredential() {
        var anchor = resolver.resolve(context, proof, NOW).orElseThrow();
        assertThat(anchor).isEqualTo(new FederatedAuthenticationAnchor(tenant.tenantId(), 4, trustId, 2,
                linkId, 3, principal, 5, identity, 6, proof.authenticatedAt(), proof.expiresAt()));
        assertThat(resolver.isStillValid(tenant, anchor, NOW.plusSeconds(1))).isTrue();
        assertThat(proof.toString()).doesNotContain("stable-subject");
    }

    @Test void rejectsCrossContextExpiryAndForeignMapping() {
        assertThat(resolver.resolve(new VerificationContext(trust, UUID.randomUUID(), context.expiresAt()), proof, NOW)).isEmpty();
        assertThat(resolver.resolve(context, proof, context.expiresAt())).isEmpty();
        var foreign = new SubjectLink(UUID.randomUUID(), linkId, 3, trustId, "stable-subject", principal);
        when(federation.activeLink(tenant, trustId, "stable-subject")).thenReturn(Optional.of(foreign));
        assertThat(resolver.resolve(context, proof, NOW)).isEmpty();
        verifyNoInteractions(identities);
    }

    @Test void currentTrustAndLinkAndSubjectRevisionsAreMandatory() {
        var anchor = resolver.resolve(context, proof, NOW).orElseThrow();
        when(federation.activeTrust(tenant, trustId)).thenReturn(Optional.of(new Trust(tenant.tenantId(),
                trustId, 3, Protocol.OIDC, "corp", "https://issuer.example", "client-a")));
        assertThat(resolver.resolve(context, proof, NOW)).isEmpty();
        assertThat(resolver.isStillValid(tenant, anchor, NOW)).isFalse();
        when(federation.activeTrust(tenant, trustId)).thenReturn(Optional.of(trust));
        when(federation.activeLinkById(tenant, linkId)).thenReturn(Optional.of(new SubjectLink(
                tenant.tenantId(), linkId, 4, trustId, "stable-subject", UUID.randomUUID())));
        assertThat(resolver.isStillValid(tenant, anchor, NOW)).isFalse();
        when(federation.activeLinkById(tenant, linkId)).thenReturn(Optional.of(link));
        when(identities.eligibleSubject(tenant, principal)).thenReturn(Optional.of(
                new IdentityAuthenticationQuery.AuthenticationSubject(principal, identity, 5, 7)));
        assertThat(resolver.isStillValid(tenant, anchor, NOW)).isFalse();
        assertThat(resolver.isStillValid(new TenantContext(UUID.randomUUID()), anchor, NOW)).isFalse();
        assertThat(resolver.isStillValid(tenant, anchor, proof.expiresAt())).isFalse();
    }

    @Test void missingOrUnavailableOwnerReadFailsClosed() {
        var anchor = resolver.resolve(context, proof, NOW).orElseThrow();
        when(federation.activeLink(tenant, trustId, "stable-subject")).thenReturn(Optional.empty());
        assertThat(resolver.resolve(context, proof, NOW)).isEmpty();
        when(federation.activeTrust(tenant, trustId)).thenThrow(new IllegalStateException("unavailable"));
        assertThat(resolver.resolve(context, proof, NOW)).isEmpty();
        assertThat(resolver.isStillValid(tenant, anchor, NOW)).isFalse();
    }
}
