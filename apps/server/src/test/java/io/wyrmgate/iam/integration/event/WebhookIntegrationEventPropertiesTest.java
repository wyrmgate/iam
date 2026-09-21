package io.wyrmgate.iam.integration.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class WebhookIntegrationEventPropertiesTest {

    @Test
    void requiresHttpsEndpointAndStrongDeploymentSecret() {
        WebhookIntegrationEventProperties http = new WebhookIntegrationEventProperties(
                URI.create("http://example.test/events"),
                "ssssssssssssssssssssssssssssssss",
                null,
                null);
        assertThatThrownBy(http::requiredEndpoint)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must use https");

        WebhookIntegrationEventProperties shortSecret = new WebhookIntegrationEventProperties(
                URI.create("https://example.test/events"),
                "too-short",
                null,
                null);
        assertThatThrownBy(shortSecret::requiredSecretBytes)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32");
    }

    @Test
    void appliesBoundedDefaultTimeouts() {
        WebhookIntegrationEventProperties properties = new WebhookIntegrationEventProperties(
                URI.create("https://example.test/events"),
                "ssssssssssssssssssssssssssssssss",
                null,
                null);

        assertThat(properties.requiredEndpoint()).isEqualTo(URI.create("https://example.test/events"));
        assertThat(properties.requiredSecretBytes()).hasSize(32);
        assertThat(properties.effectiveConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.effectiveRequestTimeout()).isEqualTo(Duration.ofSeconds(10));
    }
}
