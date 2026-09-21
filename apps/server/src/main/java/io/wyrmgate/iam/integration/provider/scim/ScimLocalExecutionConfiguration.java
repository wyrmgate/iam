package io.wyrmgate.iam.integration.provider.scim;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.access.application.DesiredAccessStateQuery;
import io.wyrmgate.iam.integration.application.ConnectorExecutionRepository;
import io.wyrmgate.iam.integration.application.ConnectorWorkRepository;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(ScimLocalExecutionProperties.class)
@ConditionalOnProperty(
        prefix = "iam.integration.scim-local",
        name = "enabled",
        havingValue = "true")
class ScimLocalExecutionConfiguration {

    @Bean
    ScimLocalExecutionService scimLocalExecutionService(
            ConnectorExecutionRepository execution,
            ConnectorWorkRepository work,
            DesiredAccessStateQuery desiredState,
            TransactionExecutor transactions,
            ScimPrincipalProviderAdapter scim,
            ScimLocalExecutionProperties properties,
            ObjectMapper json) {
        return new ScimLocalExecutionService(
                execution, work, desiredState, transactions, scim, properties, json);
    }

    @Bean
    ScimLocalExecutionScheduler scimLocalExecutionScheduler(
            ScimLocalExecutionService service) {
        return new ScimLocalExecutionScheduler(service);
    }
}
