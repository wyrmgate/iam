package io.wyrmgate.iam.integration.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WebhookIntegrationEventPublisherTest {

    private static final Instant DELIVERY_TIME = Instant.ofEpochSecond(1700000000L);
    private static final byte[] SECRET =
            "ssssssssssssssssssssssssssssssss".getBytes(StandardCharsets.UTF_8);
    private static final OutboundIntegrationEvent EVENT = new OutboundIntegrationEvent(
            "iam.identity.created.v1",
            "application/json",
            UUID.fromString("0199e100-0000-7000-8000-000000000002"),
            "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));

    @Test
    void sendsSignedHeadersToConfiguredHttpsDestination() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        WebhookIntegrationEventPublisher publisher = publisher(request -> {
            captured.set(request);
            return 204;
        });

        publisher.publish(EVENT);

        HttpRequest request = captured.get();
        assertThat(request).isNotNull();
        assertThat(request.uri()).isEqualTo(URI.create("https://events.example.test/wyrmgate"));
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.timeout()).contains(Duration.ofSeconds(10));
        assertThat(request.headers().firstValue("Content-Type")).contains("application/json");
        assertThat(request.headers().firstValue("X-Wyrmgate-Event-Id"))
                .contains("0199e100-0000-7000-8000-000000000002");
        assertThat(request.headers().firstValue("X-Wyrmgate-Event-Type"))
                .contains("iam.identity.created.v1");
        assertThat(request.headers().firstValue("X-Wyrmgate-Delivery-Timestamp"))
                .contains("1700000000");
        assertThat(request.headers().firstValue("X-Wyrmgate-Signature"))
                .contains("v1=309f315ed918800b6520ea268513d815910b912ddf2ca1d08eb7fdfaaf0e3676");
    }

    @Test
    void classifiesRetryableHttpAndIoFailures() {
        assertThatThrownBy(() -> publisher(request -> 429).publish(EVENT))
                .isInstanceOfSatisfying(
                        IntegrationEventDeliveryException.class,
                        failure -> {
                            assertThat(failure.retryable()).isTrue();
                            assertThat(failure.errorCode()).isEqualTo("webhook_http_retryable");
                        });

        assertThatThrownBy(() -> publisher(request -> {
                    throw new IOException("remote detail must not become an error code");
                }).publish(EVENT))
                .isInstanceOfSatisfying(
                        IntegrationEventDeliveryException.class,
                        failure -> {
                            assertThat(failure.retryable()).isTrue();
                            assertThat(failure.errorCode()).isEqualTo("webhook_io_retryable");
                        });
    }

    @Test
    void classifiesRedirectAndOrdinaryClientErrorsAsTerminal() {
        for (int status : new int[] {302, 400, 401, 404}) {
            assertThatThrownBy(() -> publisher(request -> status).publish(EVENT))
                    .isInstanceOfSatisfying(
                            IntegrationEventDeliveryException.class,
                            failure -> {
                                assertThat(failure.retryable()).isFalse();
                                assertThat(failure.errorCode()).isEqualTo("webhook_http_terminal");
                            });
        }
    }

    @Test
    void rejectsNonHttpsEndpointEvenInDirectConstruction() {
        assertThatThrownBy(() -> new WebhookIntegrationEventPublisher(
                        request -> 204,
                        URI.create("http://example.test/events"),
                        Duration.ofSeconds(10),
                        SECRET,
                        Clock.fixed(DELIVERY_TIME, ZoneOffset.UTC)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https");
    }

    private static WebhookIntegrationEventPublisher publisher(
            WebhookIntegrationEventPublisher.WebhookHttpTransport transport) {
        return new WebhookIntegrationEventPublisher(
                transport,
                URI.create("https://events.example.test/wyrmgate"),
                Duration.ofSeconds(10),
                SECRET,
                Clock.fixed(DELIVERY_TIME, ZoneOffset.UTC));
    }
}
