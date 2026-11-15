package io.wyrmgate.iam.idp.federation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.wyrmgate.iam.integration.application.federation.FederatedAuthenticationAdapter;
import io.wyrmgate.iam.integration.application.federation.VerifiedFederatedSubject;
import io.wyrmgate.iam.integration.application.federation.FederationTrustQuery.*;
import java.util.UUID;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class FederationAdapterRegistryTest {

    @Test
    void emptyRegistryEnablesNoFederationProviderImplicitly() {
        FederationAdapterRegistry registry = new FederationAdapterRegistry(List.of());

        assertThat(registry.descriptors()).isEmpty();
        assertThat(registry.descriptor("oidc-corp")).isEmpty();
    }

    @Test
    void registersOnlyExplicitTypedProviderDescriptors() {
        FederatedAuthenticationAdapter<OidcCallback> adapter = new OidcAdapter("oidc-corp");
        FederationAdapterRegistry registry = new FederationAdapterRegistry(List.of(adapter));

        assertThat(registry.descriptor("oidc-corp")).contains(
                new FederationAdapterRegistry.Descriptor(
                        "oidc-corp", Protocol.OIDC, OidcCallback.class.getName()));
        Instant now = Instant.parse("2026-10-10T05:00:00Z");
        var context = new VerificationContext(new Trust(UUID.randomUUID(), UUID.randomUUID(), 1,
                Protocol.OIDC, "oidc-corp", "https://issuer.example.test", "client"), UUID.randomUUID(), now.plusSeconds(60));
        VerifiedFederatedSubject verified = adapter.authenticate(context, new OidcCallback("subject-123"), now);
        assertThat(verified.exactSubject()).isEqualTo("subject-123");
        assertThat(verified.context()).isEqualTo(context);
    }

    @Test
    void duplicateProviderKeyFailsClosed() {
        assertThatThrownBy(() -> new FederationAdapterRegistry(List.of(
                        new OidcAdapter("duplicate"),
                        new OidcAdapter("duplicate"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate federation provider key");
    }

    private record OidcCallback(String subject) {
    }

    private static final class OidcAdapter
            implements FederatedAuthenticationAdapter<OidcCallback> {
        private final String providerKey;

        private OidcAdapter(String providerKey) {
            this.providerKey = providerKey;
        }

        @Override
        public String providerKey() {
            return providerKey;
        }

        @Override
        public Protocol protocol() {
            return Protocol.OIDC;
        }

        @Override
        public Class<OidcCallback> requestType() {
            return OidcCallback.class;
        }

        @Override
        public VerifiedFederatedSubject authenticate(VerificationContext context, OidcCallback request, Instant now) {
            return new VerifiedFederatedSubject(context, request.subject(), now, now.plusSeconds(60));
        }
    }
}
