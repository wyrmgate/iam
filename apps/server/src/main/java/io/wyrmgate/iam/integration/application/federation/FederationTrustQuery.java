package io.wyrmgate.iam.integration.application.federation;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Integration-owned semantic reads; neither observations nor Administration actor bindings. */
public interface FederationTrustQuery {
    Optional<Trust> activeTrust(TenantContext tenant, UUID trustId);
    Optional<SubjectLink> activeLink(TenantContext tenant, UUID trustId, String exactSubject);
    Optional<SubjectLink> activeLinkById(TenantContext tenant, UUID linkId);

    enum Protocol { OIDC, SAML }

    record Trust(UUID tenantId, UUID id, long revision, Protocol protocol, String providerKey,
                 String issuer, String audience) {
        public Trust {
            Objects.requireNonNull(tenantId); Objects.requireNonNull(id); Objects.requireNonNull(protocol);
            if (revision < 1) throw new IllegalArgumentException("invalid trust revision");
            exact(providerKey, 128); exact(issuer, 512); exact(audience, 512);
            if (!URI.create(issuer).isAbsolute()) throw new IllegalArgumentException("issuer must be absolute");
        }
    }

    record SubjectLink(UUID tenantId, UUID id, long revision, UUID trustId, String exactSubject, UUID principalId) {
        public SubjectLink {
            Objects.requireNonNull(tenantId); Objects.requireNonNull(id); Objects.requireNonNull(trustId);
            Objects.requireNonNull(principalId); exact(exactSubject, 512);
            if (revision < 1) throw new IllegalArgumentException("invalid link revision");
        }
        @Override public String toString() { return "SubjectLink[REDACTED]"; }
    }

    /** Constructed from current server trust and an already browser-bound interaction, not callback claims. */
    record VerificationContext(Trust trust, UUID interactionId, Instant expiresAt) {
        public VerificationContext {
            Objects.requireNonNull(trust); Objects.requireNonNull(interactionId); Objects.requireNonNull(expiresAt);
        }
    }

    private static void exact(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max || !value.equals(value.strip())) {
            throw new IllegalArgumentException("invalid federation identifier");
        }
    }
}
