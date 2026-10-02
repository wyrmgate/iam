package io.wyrmgate.iam.administration.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class BreakGlassNotificationPropertiesTest {

    @Test
    void validatesHttpsSecretAndDefaultsWithoutLeakingSecret() {
        String secret = "01234567890123456789012345678901";
        var properties = new BreakGlassNotificationProperties(
                true,
                URI.create("https://security.example/hooks/wyrmgate"),
                secret,
                null, null, null, null, null, null, null, null);

        assertThat(properties.requiredEndpoint().getScheme()).isEqualTo("https");
        assertThat(properties.requiredSecretBytes()).hasSize(32);
        assertThat(properties.effectiveConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.effectiveMaxAttempts()).isEqualTo(8);
        assertThat(properties.toString()).doesNotContain(secret);
    }

    @Test
    void rejectsInsecureEndpointAndShortSecret() {
        var insecure = new BreakGlassNotificationProperties(
                true, URI.create("http://security.example/hook"),
                "01234567890123456789012345678901",
                null, null, null, null, null, null, null, null);
        assertThatThrownBy(insecure::requiredEndpoint)
                .isInstanceOf(IllegalStateException.class);

        var shortSecret = new BreakGlassNotificationProperties(
                true, URI.create("https://security.example/hook"),
                "too-short",
                null, null, null, null, null, null, null, null);
        assertThatThrownBy(shortSecret::requiredSecretBytes)
                .isInstanceOf(IllegalStateException.class);
    }
}
