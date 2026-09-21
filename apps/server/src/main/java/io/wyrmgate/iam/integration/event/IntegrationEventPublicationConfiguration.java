package io.wyrmgate.iam.integration.event;

import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Conditional runtime composition for public integration-event publication.
 *
 * <p>No transport adapter is selected here. Enabling publication without an
 * IntegrationEventPublisher bean fails application startup rather than dropping
 * or pretending to publish events.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(IntegrationEventPublicationProperties.class)
@ConditionalOnProperty(prefix = "iam.integration.events", name = "enabled", havingValue = "true")
class IntegrationEventPublicationConfiguration {

    @Bean
    IdentityIntegrationEventMapper identityIntegrationEventMapper() {
        return new IdentityIntegrationEventMapper();
    }

    @Bean
    IntegrationEventJsonEncoder integrationEventJsonEncoder() {
        return new IntegrationEventJsonEncoder();
    }

    @Bean
    IntegrationEventPublicationService integrationEventPublicationService(
            JdbcOutboxRepository outbox,
            IdentityIntegrationEventMapper mapper,
            IntegrationEventJsonEncoder encoder,
            IntegrationEventPublisher publisher,
            IntegrationEventPublicationProperties properties) {
        return new IntegrationEventPublicationService(outbox, mapper, encoder, publisher, properties);
    }

    @Bean
    IntegrationEventPublicationScheduler integrationEventPublicationScheduler(
            IntegrationEventPublicationService service) {
        return new IntegrationEventPublicationScheduler(service);
    }
}
