package io.wyrmgate.iam.administration.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassNotificationDeliveryException;
import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassNotificationPublisher;
import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassNotificationRepository;
import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassNotificationWork;
import io.wyrmgate.iam.administration.domain.AdministrativeBreakGlassOperation;
import io.wyrmgate.iam.administration.domain.AdministrativeBreakGlassState;
import io.wyrmgate.iam.administration.domain.AdministrativeScope;
import io.wyrmgate.iam.administration.domain.AuthenticationAssuranceLevel;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BreakGlassNotificationDeliveryServiceTest {

    private final Instant now = Instant.parse("2026-10-02T01:00:00Z");
    private final TenantContext tenant = new TenantContext(UUID.randomUUID());
    private final UUID obligationId = UUID.randomUUID();

    @Test
    void successfulDeliveryCompletesObligation() {
        var repository = mock(AdministrativeBreakGlassNotificationRepository.class);
        var publisher = mock(AdministrativeBreakGlassNotificationPublisher.class);
        when(repository.claimPending(any(), any(), eq(25)))
                .thenReturn(List.of(work(1)));

        var service = service(repository, publisher, 8);
        var result = service.deliverAvailable();

        assertThat(result.completed()).isEqualTo(1);
        verify(repository).markCompleted(tenant, obligationId, 1, now);
    }

    @Test
    void retryableFailureRetriesUntilAttemptLimitThenRequiresManualWork() {
        var repository = mock(AdministrativeBreakGlassNotificationRepository.class);
        var publisher = mock(AdministrativeBreakGlassNotificationPublisher.class);
        when(repository.claimPending(any(), any(), eq(25)))
                .thenReturn(List.of(work(8)));
        org.mockito.Mockito.doThrow(new AdministrativeBreakGlassNotificationDeliveryException(
                        true, "break_glass_notification_http_retryable"))
                .when(publisher).publish(any());

        var service = service(repository, publisher, 8);
        var result = service.deliverAvailable();

        assertThat(result.manualRequired()).isEqualTo(1);
        verify(repository).markManualRequired(
                tenant,
                obligationId,
                8,
                "break_glass_notification_http_retryable",
                now);
    }

    @Test
    void terminalFailureImmediatelyRequiresManualWork() {
        var repository = mock(AdministrativeBreakGlassNotificationRepository.class);
        var publisher = mock(AdministrativeBreakGlassNotificationPublisher.class);
        when(repository.claimPending(any(), any(), eq(25)))
                .thenReturn(List.of(work(1)));
        org.mockito.Mockito.doThrow(new AdministrativeBreakGlassNotificationDeliveryException(
                        false, "break_glass_notification_http_terminal"))
                .when(publisher).publish(any());

        var service = service(repository, publisher, 8);
        var result = service.deliverAvailable();

        assertThat(result.manualRequired()).isEqualTo(1);
        verify(repository).markManualRequired(
                tenant,
                obligationId,
                1,
                "break_glass_notification_http_terminal",
                now);
    }

    private BreakGlassNotificationDeliveryService service(
            AdministrativeBreakGlassNotificationRepository repository,
            AdministrativeBreakGlassNotificationPublisher publisher,
            int maxAttempts) {
        var properties = new BreakGlassNotificationProperties(
                true,
                URI.create("https://security.example/hook"),
                "01234567890123456789012345678901",
                null, null, null, null, 25, maxAttempts, null, null);
        return new BreakGlassNotificationDeliveryService(
                repository, publisher, properties, Clock.fixed(now, ZoneOffset.UTC));
    }

    private AdministrativeBreakGlassNotificationWork work(int attempt) {
        var operation = new AdministrativeBreakGlassOperation(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                AdministrativeScope.global(),
                "sensitive reason",
                "INC-42",
                now.minusSeconds(30),
                now.plusSeconds(300),
                AuthenticationAssuranceLevel.STRONG,
                now.minusSeconds(60),
                now.minusSeconds(60),
                300,
                AdministrativeBreakGlassState.ACTIVE,
                now.minusSeconds(30),
                null,
                null,
                UUID.randomUUID(),
                null,
                1,
                now.minusSeconds(30),
                now.minusSeconds(30));
        return new AdministrativeBreakGlassNotificationWork(
                tenant, obligationId, attempt, operation);
    }
}
