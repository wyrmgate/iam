package io.wyrmgate.iam.integration.event;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/** Single-destination signed HTTPS webhook adapter defined by ADR-0013. */
public final class WebhookIntegrationEventPublisher implements IntegrationEventPublisher {

    private static final String RETRYABLE_HTTP = "webhook_http_retryable";
    private static final String TERMINAL_HTTP = "webhook_http_terminal";
    private static final String RETRYABLE_IO = "webhook_io_retryable";

    private final WebhookHttpTransport transport;
    private final URI endpoint;
    private final Duration requestTimeout;
    private final WebhookRequestSigner signer;
    private final Clock clock;

    public WebhookIntegrationEventPublisher(
            HttpClient httpClient,
            URI endpoint,
            Duration requestTimeout,
            byte[] secret) {
        this(
                request -> httpClient
                        .send(request, HttpResponse.BodyHandlers.discarding())
                        .statusCode(),
                endpoint,
                requestTimeout,
                secret,
                Clock.systemUTC());
    }

    WebhookIntegrationEventPublisher(
            WebhookHttpTransport transport,
            URI endpoint,
            Duration requestTimeout,
            byte[] secret,
            Clock clock) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (!"https".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IllegalArgumentException("webhook endpoint must use https");
        }
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        this.signer = new WebhookRequestSigner(secret);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void publish(OutboundIntegrationEvent event) {
        Objects.requireNonNull(event, "event");
        byte[] body = event.payload();
        long unixSeconds = clock.instant().getEpochSecond();

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", event.contentType())
                .header("X-Wyrmgate-Event-Id", event.eventId().toString())
                .header("X-Wyrmgate-Event-Type", event.address())
                .header("X-Wyrmgate-Delivery-Timestamp", Long.toString(unixSeconds))
                .header("X-Wyrmgate-Signature", signer.sign(unixSeconds, body))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        try {
            WebhookHttpOutcome outcome = WebhookHttpOutcome.classify(transport.send(request));
            if (outcome == WebhookHttpOutcome.SUCCESS) {
                return;
            }
            if (outcome == WebhookHttpOutcome.RETRYABLE_FAILURE) {
                throw new IntegrationEventDeliveryException(true, RETRYABLE_HTTP);
            }
            throw new IntegrationEventDeliveryException(false, TERMINAL_HTTP);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IntegrationEventDeliveryException(true, RETRYABLE_IO, exception);
        } catch (IOException exception) {
            throw new IntegrationEventDeliveryException(true, RETRYABLE_IO, exception);
        }
    }

    @FunctionalInterface
    interface WebhookHttpTransport {
        int send(HttpRequest request) throws IOException, InterruptedException;
    }
}
