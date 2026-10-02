package io.wyrmgate.iam.administration.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassNotificationDeliveryException;
import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassSecurityNotification;
import io.wyrmgate.iam.administration.domain.AdministrativeScope;
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

class SignedHttpsBreakGlassNotificationPublisherTest {

    private static final Instant DELIVERY_TIME = Instant.ofEpochSecond(1_700_000_000L);
    private static final byte[] SECRET =
            "ssssssssssssssssssssssssssssssss".getBytes(StandardCharsets.UTF_8);

    @Test
    void sendsSignedDataMinimizedPayloadWithoutReasonOrAssuranceEvidence() {
        AtomicReference<HttpRequest> capturedRequest = new AtomicReference<>();
        AtomicReference<byte[]> capturedBody = new AtomicReference<>();
        var publisher = publisher((request, body) -> {
            capturedRequest.set(request);
            capturedBody.set(body.clone());
            return 204;
        });

        publisher.publish(notification());

        HttpRequest request = capturedRequest.get();
        String body = new String(capturedBody.get(), StandardCharsets.UTF_8);
        assertThat(request.uri()).isEqualTo(URI.create("https://security.example.test/break-glass"));
        assertThat(request.headers().firstValue("X-Wyrmgate-Notification-Id"))
                .contains("0199e100-0000-7000-8000-000000000011");
        assertThat(request.headers().firstValue("X-Wyrmgate-Notification-Type"))
                .contains("iam.administration.break-glass.security-notification.v1");
        assertThat(request.headers().firstValue("X-Wyrmgate-Delivery-Timestamp"))
                .contains("1700000000");
        assertThat(request.headers().firstValue("X-Wyrmgate-Signature"))
                .get().asString().startsWith("v1=");

        assertThat(body)
                .contains("\"version\":\"1\"")
                .contains("\"type\":\"iam.administration.break-glass.security-notification.v1\"")
                .contains("\"incidentReference\":\"INC-2026-42\"")
                .doesNotContain("sensitive emergency reason")
                .doesNotContain("activationAssurance")
                .doesNotContain("authenticatedAt")
                .doesNotContain("stepUpAt")
                .doesNotContain("permission");
    }

    @Test
    void classifiesRetryableAndTerminalResponsesWithoutRemoteBodyPersistence() {
        assertThatThrownBy(() -> publisher((request, body) -> 429).publish(notification()))
                .isInstanceOfSatisfying(
                        AdministrativeBreakGlassNotificationDeliveryException.class,
                        failure -> {
                            assertThat(failure.retryable()).isTrue();
                            assertThat(failure.errorCode())
                                    .isEqualTo("break_glass_notification_http_retryable");
                        });

        assertThatThrownBy(() -> publisher((request, body) -> 401).publish(notification()))
                .isInstanceOfSatisfying(
                        AdministrativeBreakGlassNotificationDeliveryException.class,
                        failure -> {
                            assertThat(failure.retryable()).isFalse();
                            assertThat(failure.errorCode())
                                    .isEqualTo("break_glass_notification_http_terminal");
                        });

        assertThatThrownBy(() -> publisher((request, body) -> {
                    throw new IOException("provider response detail must not become persisted state");
                }).publish(notification()))
                .isInstanceOfSatisfying(
                        AdministrativeBreakGlassNotificationDeliveryException.class,
                        failure -> {
                            assertThat(failure.retryable()).isTrue();
                            assertThat(failure.errorCode())
                                    .isEqualTo("break_glass_notification_io_retryable");
                        });
    }

    @Test
    void directConstructionRejectsNonHttpsEndpoint() {
        assertThatThrownBy(() -> new SignedHttpsBreakGlassNotificationPublisher(
                        (request, body) -> 204,
                        URI.create("http://security.example.test/break-glass"),
                        Duration.ofSeconds(10),
                        SECRET,
                        new ObjectMapper(),
                        Clock.fixed(DELIVERY_TIME, ZoneOffset.UTC)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https");
    }

    private static SignedHttpsBreakGlassNotificationPublisher publisher(
            SignedHttpsBreakGlassNotificationPublisher.BreakGlassHttpTransport transport) {
        return new SignedHttpsBreakGlassNotificationPublisher(
                transport,
                URI.create("https://security.example.test/break-glass"),
                Duration.ofSeconds(10),
                SECRET,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(DELIVERY_TIME, ZoneOffset.UTC));
    }

    private static AdministrativeBreakGlassSecurityNotification notification() {
        return new AdministrativeBreakGlassSecurityNotification(
                UUID.fromString("0199e100-0000-7000-8000-000000000011"),
                UUID.fromString("0199e100-0000-7000-8000-000000000012"),
                UUID.fromString("0199e100-0000-7000-8000-000000000013"),
                UUID.fromString("0199e100-0000-7000-8000-000000000014"),
                UUID.fromString("0199e100-0000-7000-8000-000000000015"),
                AdministrativeScope.global(),
                "INC-2026-42",
                Instant.parse("2026-10-02T01:00:00Z"),
                Instant.parse("2026-10-02T01:10:00Z"),
                UUID.fromString("0199e100-0000-7000-8000-000000000016"),
                null);
    }
}
