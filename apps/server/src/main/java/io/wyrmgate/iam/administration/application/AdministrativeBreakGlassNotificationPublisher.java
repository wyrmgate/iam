package io.wyrmgate.iam.administration.application;

/** External ADR-0033 security-notification adapter. */
@FunctionalInterface
public interface AdministrativeBreakGlassNotificationPublisher {
    void publish(AdministrativeBreakGlassSecurityNotification notification);
}
