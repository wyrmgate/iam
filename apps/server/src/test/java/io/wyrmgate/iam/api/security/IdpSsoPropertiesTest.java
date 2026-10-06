package io.wyrmgate.iam.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IdpSsoPropertiesTest {

    @Test
    void acceptsHttpsIssuerAndNormalizesTrailingSlash() {
        IdpSsoProperties properties = new IdpSsoProperties(true, "https://iam.example.test/");

        assertThat(properties.requiredIssuer()).isEqualTo("https://iam.example.test");
    }

    @Test
    void acceptsLoopbackHttpForLocalDevelopment() {
        assertThat(new IdpSsoProperties(true, "http://localhost:8080").requiredIssuer())
                .isEqualTo("http://localhost:8080");
        assertThat(new IdpSsoProperties(true, "http://127.0.0.1:8080").requiredIssuer())
                .isEqualTo("http://127.0.0.1:8080");
    }

    @Test
    void rejectsMissingIssuer() {
        assertThatThrownBy(() -> new IdpSsoProperties(true, " ").requiredIssuer())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("iam.sso.issuer is required");
    }

    @Test
    void rejectsNonLoopbackPlainHttpIssuer() {
        assertThatThrownBy(() -> new IdpSsoProperties(true, "http://iam.example.test").requiredIssuer())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must use HTTPS");
    }

    @Test
    void rejectsIssuerWithQueryFragmentOrUserInfo() {
        assertThatThrownBy(() -> new IdpSsoProperties(true, "https://iam.example.test?tenant=one").requiredIssuer())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without user-info, query, or fragment");
        assertThatThrownBy(() -> new IdpSsoProperties(true, "https://user@iam.example.test").requiredIssuer())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without user-info, query, or fragment");
    }
}
