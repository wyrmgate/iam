package io.wyrmgate.iam.audit.siem;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/** ADR-0037 single-destination signed HTTPS SIEM adapter. */
public final class SignedHttpsAuditSiemPublisher implements AuditSiemPublisher {

    private final HttpTransport transport;
    private final URI endpoint;
    private final Duration requestTimeout;
    private final AuditSiemSigner signer;
    private final ObjectMapper json;
    private final Clock clock;

    public SignedHttpsAuditSiemPublisher(
            HttpClient client,
            URI endpoint,
            Duration requestTimeout,
            byte[] secret,
            ObjectMapper json) {
        this(
                request -> client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode(),
                endpoint,
                requestTimeout,
                secret,
                json,
                Clock.systemUTC());
    }

    SignedHttpsAuditSiemPublisher(
            HttpTransport transport,
            URI endpoint,
            Duration requestTimeout,
            byte[] secret,
            ObjectMapper json,
            Clock clock) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (!"https".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IllegalArgumentException("SIEM endpoint must use https");
        }
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        this.signer = new AuditSiemSigner(secret);
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void publish(AuditSiemMessage message) {
        final byte[] body;
        try {
            body = json.writeValueAsBytes(message);
        } catch (JsonProcessingException error) {
            throw new AuditSiemDeliveryException(false, "audit_siem_encoding_failed", error);
        }
        long timestamp = clock.instant().getEpochSecond();
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("X-Wyrmgate-SIEM-Message-Id", message.messageId().toString())
                .header("X-Wyrmgate-SIEM-Version", message.version())
                .header("X-Wyrmgate-Delivery-Timestamp", Long.toString(timestamp))
                .header("X-Wyrmgate-Signature", signer.sign(timestamp, body))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try {
            int status = transport.send(request);
            if (status >= 200 && status < 300) return;
            boolean retryable = status == 408 || status == 425 || status == 429 || status >= 500;
            throw new AuditSiemDeliveryException(
                    retryable,
                    retryable ? "audit_siem_http_retryable" : "audit_siem_http_terminal");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AuditSiemDeliveryException(true, "audit_siem_io_retryable", error);
        } catch (IOException error) {
            throw new AuditSiemDeliveryException(true, "audit_siem_io_retryable", error);
        }
    }

    @FunctionalInterface
    interface HttpTransport {
        int send(HttpRequest request) throws IOException, InterruptedException;
    }
}
