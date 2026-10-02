package io.wyrmgate.iam.administration.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassNotificationPublisher;
import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassNotificationRepository;
import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassRepository;
import io.wyrmgate.iam.administration.persistence.JdbcAdministrativeBreakGlassNotificationRepository;
import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(BreakGlassNotificationProperties.class)
@ConditionalOnProperty(
        prefix = "iam.administration.break-glass.notification",
        name = "enabled",
        havingValue = "true")
class BreakGlassNotificationConfiguration {

    @Bean
    AdministrativeBreakGlassNotificationRepository administrativeBreakGlassNotificationRepository(
            JdbcTemplate jdbc,
            AdministrativeBreakGlassRepository breakGlass) {
        return new JdbcAdministrativeBreakGlassNotificationRepository(jdbc, breakGlass);
    }

    @Bean
    AdministrativeBreakGlassNotificationPublisher administrativeBreakGlassNotificationPublisher(
            BreakGlassNotificationProperties properties,
            ObjectMapper objectMapper) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(properties.effectiveConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new SignedHttpsBreakGlassNotificationPublisher(
                client,
                properties.requiredEndpoint(),
                properties.effectiveRequestTimeout(),
                properties.requiredSecretBytes(),
                objectMapper);
    }

    @Bean
    BreakGlassNotificationDeliveryService breakGlassNotificationDeliveryService(
            AdministrativeBreakGlassNotificationRepository repository,
            AdministrativeBreakGlassNotificationPublisher publisher,
            BreakGlassNotificationProperties properties) {
        return new BreakGlassNotificationDeliveryService(repository, publisher, properties);
    }

    @Bean
    BreakGlassNotificationScheduler breakGlassNotificationScheduler(
            BreakGlassNotificationDeliveryService service) {
        return new BreakGlassNotificationScheduler(service);
    }
}
