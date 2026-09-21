package io.wyrmgate.iam.integration.event;

import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Runtime composition for the ADR-0013 single-destination webhook adapter. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebhookIntegrationEventProperties.class)
@ConditionalOnProperty(
        prefix = "iam.integration.events",
        name = "transport",
        havingValue = "webhook")
class WebhookIntegrationEventConfiguration {

    @Bean
    IntegrationEventPublisher webhookIntegrationEventPublisher(
            WebhookIntegrationEventProperties properties) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(properties.effectiveConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new WebhookIntegrationEventPublisher(
                client,
                properties.requiredEndpoint(),
                properties.effectiveRequestTimeout(),
                properties.requiredSecretBytes());
    }
}
