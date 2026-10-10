package io.wyrmgate.iam.idp.federation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.wyrmgate.iam.administration.domain.ExternalAuthenticationSubject;
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
                        "oidc-corp", FederationProtocol.OIDC, OidcCallback.class.getName()));
        VerifiedFederatedSubject verified = adapter.authenticate(
                new OidcCallback("subject-123"), Instant.parse("2026-10-10T05:00:00Z"));
        assertThat(verified.subject())
                .isEqualTo(new ExternalAuthenticationSubject(
                        "https://issuer.example.test", "subject-123"));
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
        public FederationProtocol protocol() {
            return FederationProtocol.OIDC;
        }

        @Override
        public Class<OidcCallback> requestType() {
            return OidcCallback.class;
        }

        @Override
        public VerifiedFederatedSubject authenticate(OidcCallback request, Instant now) {
            return new VerifiedFederatedSubject(
                    new ExternalAuthenticationSubject(
                            "https://issuer.example.test", request.subject()),
                    now);
        }
    }
}
