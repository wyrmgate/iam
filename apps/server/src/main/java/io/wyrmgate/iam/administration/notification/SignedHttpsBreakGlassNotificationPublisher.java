package io.wyrmgate.iam.administration.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassNotificationDeliveryException;
import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassNotificationPublisher;
import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassSecurityNotification;
import io.wyrmgate.iam.administration.domain.AdministrativeScope;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** ADR-0033 single-destination signed HTTPS security-notification adapter. */
public final class SignedHttpsBreakGlassNotificationPublisher
        implements AdministrativeBreakGlassNotificationPublisher {

    private final BreakGlassHttpTransport transport;
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
        this(
                httpTransport(client),
                endpoint,
                requestTimeout,
                secret,
                json,
                Clock.systemUTC());
    }

    SignedHttpsBreakGlassNotificationPublisher(
            BreakGlassHttpTransport transport,
            URI endpoint,
            Duration requestTimeout,
            byte[] secret,
            ObjectMapper json,
            Clock clock) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (!"https".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IllegalArgumentException("notification endpoint must use https");
        }
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        this.signer = new BreakGlassNotificationSigner(secret);
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    private static BreakGlassHttpTransport httpTransport(HttpClient client) {
        Objects.requireNonNull(client, "client");
        return (request, body) -> client
                .send(request, HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    @Override
    public void publish(AdministrativeBreakGlassSecurityNotification notification) {
        WireNotification wire = WireNotification.from(notification);
        final byte[] body;
        try {
            body = json.writeValueAsBytes(wire);
        } catch (JsonProcessingException exception) {
            throw new AdministrativeBreakGlassNotificationDeliveryException(
                    false, "break_glass_notification_encoding_failed", exception);
        }

        long unixSeconds = clock.instant().getEpochSecond();
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("X-Wyrmgate-Notification-Id", notification.notificationId().toString())
                .header("X-Wyrmgate-Notification-Type", AdministrativeBreakGlassSecurityNotification.TYPE)
                .header("X-Wyrmgate-Delivery-Timestamp", Long.toString(unixSeconds))
                .header("X-Wyrmgate-Signature", signer.sign(unixSeconds, body))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        try {
            int status = transport.send(request, body);
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

    @FunctionalInterface
    interface BreakGlassHttpTransport {
        int send(HttpRequest request, byte[] body) throws IOException, InterruptedException;
    }

    private record WireNotification(
            String version,
            String type,
            UUID notificationId,
            UUID tenantId,
            UUID breakGlassOperationId,
            UUID actorIdentityId,
            UUID roleId,
            AdministrativeScope scope,
            String incidentReference,
            Instant activatedAt,
            Instant validUntil,
            UUID correlationId,
            UUID causationId) {

        static WireNotification from(AdministrativeBreakGlassSecurityNotification source) {
            return new WireNotification(
                    "1",
                    AdministrativeBreakGlassSecurityNotification.TYPE,
                    source.notificationId(),
                    source.tenantId(),
                    source.breakGlassOperationId(),
                    source.actorIdentityId(),
                    source.roleId(),
                    source.scope(),
                    source.incidentReference(),
                    source.activatedAt(),
                    source.validUntil(),
                    source.correlationId(),
                    source.causationId());
        }
    }
}
