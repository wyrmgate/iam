package io.wyrmgate.iam.audit.siem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.audit.domain.AuditMaterialSnapshot;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SignedHttpsAuditSiemPublisherTest {

    @Test
    void signsClosedMessageAndTreats2xxAsAccepted() {
        AtomicReference<HttpRequest> request = new AtomicReference<>();
        byte[] secret = Base64.getDecoder().decode(
                "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        SignedHttpsAuditSiemPublisher publisher = new SignedHttpsAuditSiemPublisher(
                value -> {
                    request.set(value);
                    return 204;
                },
                URI.create("https://siem.example.test/audit"),
                Duration.ofSeconds(2),
                secret,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(Instant.parse("2026-10-02T08:00:00Z"), ZoneOffset.UTC));

        UUID id = UUID.randomUUID();
        publisher.publish(new AuditSiemMessage(
                AuditSiemMessage.VERSION,
                id,
                UUID.randomUUID(),
                id,
                Instant.parse("2026-10-02T07:59:00Z"),
                Instant.parse("2026-10-02T07:59:01Z"),
                UUID.randomUUID(),
                "identity:update",
                "identity",
                UUID.randomUUID(),
                "SUCCESS",
                UUID.randomUUID(),
                null,
                new AuditMaterialSnapshot(null, "Example", 2L, "ACTIVE"),
                null));

        assertThat(request.get()).isNotNull();
        assertThat(request.get().headers().firstValue("X-Wyrmgate-SIEM-Message-Id"))
                .contains(id.toString());
        assertThat(request.get().headers().firstValue("X-Wyrmgate-SIEM-Version"))
                .contains(AuditSiemMessage.VERSION);
        assertThat(request.get().headers().firstValue("X-Wyrmgate-Signature").orElseThrow())
                .matches("v1=[0-9a-f]{64}");
    }

    @Test
    void classifiesRetryableAndTerminalHttpFailures() {
        byte[] secret = new byte[32];
        AuditSiemMessage message = new AuditSiemMessage(
                AuditSiemMessage.VERSION,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now(),
                Instant.now(),
                null,
                "identity:update",
                "identity",
                UUID.randomUUID(),
                "SUCCESS",
                null,
                null,
                null,
                null);

        SignedHttpsAuditSiemPublisher retryable = publisher(secret, 429);
        assertThatThrownBy(() -> retryable.publish(message))
                .isInstanceOfSatisfying(
                        AuditSiemDeliveryException.class,
                        failure -> assertThat(failure.retryable()).isTrue());

        SignedHttpsAuditSiemPublisher terminal = publisher(secret, 400);
        assertThatThrownBy(() -> terminal.publish(message))
                .isInstanceOfSatisfying(
                        AuditSiemDeliveryException.class,
                        failure -> assertThat(failure.retryable()).isFalse());
    }

    @Test
    void rejectsNonHttpsDestination() {
        assertThatThrownBy(() -> new SignedHttpsAuditSiemPublisher(
                        request -> 204,
                        URI.create("http://siem.example.test/audit"),
                        Duration.ofSeconds(1),
                        new byte[32],
                        new ObjectMapper(),
                        Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SIEM endpoint must use https");
    }

    private static SignedHttpsAuditSiemPublisher publisher(byte[] secret, int status) {
        return new SignedHttpsAuditSiemPublisher(
                request -> status,
                URI.create("https://siem.example.test/audit"),
                Duration.ofSeconds(1),
                secret,
                new ObjectMapper().findAndRegisterModules(),
                Clock.systemUTC());
    }
}
