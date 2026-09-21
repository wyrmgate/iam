package io.wyrmgate.iam.integration.provider.scim;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.integration.provider.ConnectorSecretProvider;
import io.wyrmgate.iam.integration.provider.EnvironmentConnectorSecretProvider;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ScimProviderConfiguration {

    @Bean
    ConnectorSecretProvider connectorSecretProvider() {
        return new EnvironmentConnectorSecretProvider();
    }

    @Bean
    ScimPrincipalProviderAdapter scimPrincipalProviderAdapter(
            ObjectMapper objectMapper,
            ConnectorSecretProvider connectorSecretProvider) {
        return new ScimPrincipalProviderAdapter(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build(),
                objectMapper,
                connectorSecretProvider);
    }
}
