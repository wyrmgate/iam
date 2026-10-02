package io.wyrmgate.iam.administration.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassNotification;
import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassNotificationDeliveryException;
import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassNotificationPublisher;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/** ADR-0033 single-destination signed HTTPS security-notification adapter. */
public final class SignedHttpsBreakGlassNotificationPublisher
        implements AdministrativeBreakGlassNotificationPublisher {

    private final HttpClient client;
    private final URI endpoint;
    private final Duration requestTimeout;
    private final BreakGlassNotificationSigner signer;
    private final ObjectMapper json;
    private final Clock clock;

    public SignedHttpsBreakGlassNotificationPublisher(
            HttpClient client,
            URI endpoint,
            Duration requestTimeout,
            byte[] secret,
            ObjectMapper json) {
        this(client, endpoint, requestTimeout, secret, json, Clock.systemUTC());
    }

    SignedHttpsBreakGlassNotificationPublisher(
            HttpClient client,
            URI endpoint,
            Duration requestTimeout,
            byte[] secret,
            ObjectMapper json,
            Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.signer = new BreakGlassNotificationSigner(secret);
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void publish(AdministrativeBreakGlassNotification notification) {
        final byte[] body;
        try {
            body = json.writeValueAsBytes(notification);
        } catch (JsonProcessingException exception) {
            throw new AdministrativeBreakGlassNotificationDeliveryException(
                    false, "break_glass_notification_encoding_failed", exception);
        }

        long unixSeconds = clock.instant().getEpochSecond();
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("X-Wyrmgate-Notification-Id", notification.obligationId().toString())
                .header("X-Wyrmgate-Notification-Type", notification.type())
                .header("X-Wyrmgate-Delivery-Timestamp", Long.toString(unixSeconds))
                .header("X-Wyrmgate-Signature", signer.sign(unixSeconds, body))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        try {
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status >= 200 && status < 300) return;
            boolean retryable = status == 408 || status == 425 || status == 429 || status >= 500;
            throw new AdministrativeBreakGlassNotificationDeliveryException(
                    retryable,
                    retryable ? "break_glass_notification_http_retryable"
                            : "break_glass_notification_http_terminal");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AdministrativeBreakGlassNotificationDeliveryException(
                    true, "break_glass_notification_io_retryable", exception);
        } catch (IOException exception) {
            throw new AdministrativeBreakGlassNotificationDeliveryException(
                    true, "break_glass_notification_io_retryable", exception);
        }
    }
}
