package io.wyrmgate.iam.administration.application;

/** External notification adapter. Calls must execute outside authoritative Administration transactions. */
@FunctionalInterface
public interface AdministrativeBreakGlassNotificationPublisher {
    void publish(AdministrativeBreakGlassNotification notification);
}
