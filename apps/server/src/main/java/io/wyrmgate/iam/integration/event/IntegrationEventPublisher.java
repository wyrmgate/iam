package io.wyrmgate.iam.integration.event;

/**
 * External-delivery port for already curated and encoded public integration events.
 *
 * <p>Transport adapters may target a broker, webhook product, cloud messaging
 * service, or another approved delivery mechanism without changing the event
 * contract or Identity domain.</p>
 */
public interface IntegrationEventPublisher {

    void publish(OutboundIntegrationEvent event);
}
