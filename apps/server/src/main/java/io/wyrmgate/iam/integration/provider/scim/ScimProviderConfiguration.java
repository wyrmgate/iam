package io.wyrmgate.iam.integration.provider.scim;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.integration.provider.ConnectorSecretProvider;
import io.wyrmgate.iam.integration.provider.EnvironmentConnectorSecretProvider;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ScimProviderConfiguration {

    @Bean
    @ConditionalOnMissingBean(ConnectorSecretProvider.class)
    ConnectorSecretProvider connectorSecretProvider() {
        return new EnvironmentConnectorSecretProvider();
    }

    @Bean
    ScimPrincipalProviderAdapter scimPrincipalProviderAdapter(
            ObjectMapper objectMapper,
            ConnectorSecretProvider connectorSecretProvider) {
        return new ScimPrincipalProviderAdapter(
                providerHttpClient(),
                objectMapper,
                connectorSecretProvider);
    }

    @Bean
    ScimGroupProviderAdapter scimGroupProviderAdapter(
            ObjectMapper objectMapper,
            ConnectorSecretProvider connectorSecretProvider) {
        return new ScimGroupProviderAdapter(
                providerHttpClient(),
                objectMapper,
                connectorSecretProvider);
    }

    private static HttpClient providerHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }
}
