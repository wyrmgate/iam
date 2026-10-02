package io.wyrmgate.iam.administration.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassNotificationDeliveryException;
import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassNotificationPublisher;
import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassNotificationScheduler;
import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassRepository;
import io.wyrmgate.iam.administration.domain.AdministrativeBreakGlassObligation;
import io.wyrmgate.iam.administration.domain.AdministrativeBreakGlassObligationState;
import io.wyrmgate.iam.administration.domain.AdministrativeBreakGlassObligationType;
import io.wyrmgate.iam.administration.domain.AdministrativeBreakGlassOperation;
import io.wyrmgate.iam.administration.domain.AdministrativeBreakGlassState;
import io.wyrmgate.iam.administration.domain.AdministrativeScope;
import io.wyrmgate.iam.administration.domain.AuthenticationAssuranceLevel;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository.ClaimedTenantWork;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository.ClaimedWork;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository.SubjectReference;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BreakGlassNotificationDeliveryServiceTest {

    private final Instant now = Instant.parse("2026-10-02T01:00:00Z");
    private final TenantContext tenant = new TenantContext(UUID.randomUUID());
    private final UUID obligationId = UUID.randomUUID();
    private final UUID operationId = UUID.randomUUID();
    private final UUID workId = UUID.randomUUID();
    private final String leaseOwner = "test-worker";

    @Test
    void successfulDeliveryCompletesAdministrationObligationAndTechnicalWork() {
        var scheduled = mock(JdbcScheduledWorkRepository.class);
        var breakGlass = mock(AdministrativeBreakGlassRepository.class);
        var publisher = mock(AdministrativeBreakGlassNotificationPublisher.class);
        stubClaim(scheduled, 1);
        stubDomain(breakGlass);

        var service = service(scheduled, breakGlass, publisher, 8);
        var result = service.deliverAvailable();

        assertThat(result.completed()).isEqualTo(1);
        verify(breakGlass).completeNotificationObligation(tenant, obligationId, now);
        verify(scheduled).markCompleted(tenant, workId, leaseOwner, now);
    }

    @Test
    void retryableFailureReschedulesPlatformWorkWithoutChangingObligation() {
        var scheduled = mock(JdbcScheduledWorkRepository.class);
        var breakGlass = mock(AdministrativeBreakGlassRepository.class);
        var publisher = mock(AdministrativeBreakGlassNotificationPublisher.class);
        stubClaim(scheduled, 1);
        stubDomain(breakGlass);
        org.mockito.Mockito.doThrow(new AdministrativeBreakGlassNotificationDeliveryException(
                        true, "break_glass_notification_http_retryable"))
                .when(publisher).publish(any());

        var service = service(scheduled, breakGlass, publisher, 8);
        var result = service.deliverAvailable();

        assertThat(result.retrying()).isEqualTo(1);
        verify(scheduled).reschedule(
                eq(tenant),
                eq(workId),
                eq(leaseOwner),
                any(Instant.class),
                eq("break_glass_notification_http_retryable"),
                eq(now));
    }

    @Test
    void terminalFailureMarksAdministrationManualRequiredAndCompletesTechnicalWork() {
        var scheduled = mock(JdbcScheduledWorkRepository.class);
        var breakGlass = mock(AdministrativeBreakGlassRepository.class);
        var publisher = mock(AdministrativeBreakGlassNotificationPublisher.class);
        stubClaim(scheduled, 1);
        stubDomain(breakGlass);
        org.mockito.Mockito.doThrow(new AdministrativeBreakGlassNotificationDeliveryException(
                        false, "break_glass_notification_http_terminal"))
                .when(publisher).publish(any());

        var service = service(scheduled, breakGlass, publisher, 8);
        var result = service.deliverAvailable();

        assertThat(result.manualRequired()).isEqualTo(1);
        verify(breakGlass).markNotificationManualRequired(tenant, obligationId, now);
        verify(scheduled).markCompleted(
                tenant,
                workId,
                leaseOwner,
                "break_glass_notification_http_terminal",
                now);
    }

    @Test
    void retryExhaustionMarksManualRequired() {
        var scheduled = mock(JdbcScheduledWorkRepository.class);
        var breakGlass = mock(AdministrativeBreakGlassRepository.class);
        var publisher = mock(AdministrativeBreakGlassNotificationPublisher.class);
        stubClaim(scheduled, 8);
        stubDomain(breakGlass);
        org.mockito.Mockito.doThrow(new AdministrativeBreakGlassNotificationDeliveryException(
                        true, "break_glass_notification_http_retryable"))
                .when(publisher).publish(any());

        var service = service(scheduled, breakGlass, publisher, 8);
        var result = service.deliverAvailable();

        assertThat(result.manualRequired()).isEqualTo(1);
        verify(breakGlass).markNotificationManualRequired(tenant, obligationId, now);
    }

    private void stubClaim(JdbcScheduledWorkRepository scheduled, int attemptCount) {
        when(scheduled.claimDueByHandler(
                        eq(AdministrativeBreakGlassNotificationScheduler.HANDLER_TYPE),
                        eq(leaseOwner),
                        eq(now),
                        any(),
                        eq(25)))
                .thenReturn(List.of(new ClaimedTenantWork(
                        tenant,
                        new ClaimedWork(
                                workId,
                                AdministrativeBreakGlassNotificationScheduler.HANDLER_TYPE,
                                obligationId.toString(),
                                new SubjectReference(
                                        "administrative-break-glass-operation",
                                        operationId,
                                        1),
                                attemptCount,
                                now.plusSeconds(30)))));
    }

    private void stubDomain(AdministrativeBreakGlassRepository breakGlass) {
        when(breakGlass.findObligation(tenant, obligationId))
                .thenReturn(Optional.of(new AdministrativeBreakGlassObligation(
                        obligationId,
                        operationId,
                        AdministrativeBreakGlassObligationType.SECURITY_NOTIFICATION,
                        AdministrativeBreakGlassObligationState.PENDING,
                        null,
                        1,
                        now.minusSeconds(30),
                        now.minusSeconds(30))));
        when(breakGlass.find(tenant, operationId)).thenReturn(Optional.of(operation()));
    }

    private BreakGlassNotificationDeliveryService service(
            JdbcScheduledWorkRepository scheduled,
            AdministrativeBreakGlassRepository breakGlass,
            AdministrativeBreakGlassNotificationPublisher publisher,
            int maxAttempts) {
        var properties = new BreakGlassNotificationProperties(
                true,
                URI.create("https://security.example/hook"),
                "01234567890123456789012345678901",
                null, null, null, null, 25, maxAttempts, null, null);
        return new BreakGlassNotificationDeliveryService(
                scheduled,
                breakGlass,
                publisher,
                properties,
                Clock.fixed(now, ZoneOffset.UTC),
                () -> 0.5,
                leaseOwner);
    }

    private AdministrativeBreakGlassOperation operation() {
        return new AdministrativeBreakGlassOperation(
                operationId,
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
    }
}
