package io.wyrmgate.iam.integration.provider.scim;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.access.application.DesiredAccessStateQuery;
import io.wyrmgate.iam.integration.application.ConnectorExecutionRepository;
import io.wyrmgate.iam.integration.application.ProvisioningAdmission;
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
            ScimGroupProviderAdapter groups,
            ScimLocalExecutionProperties properties,
            ObjectMapper json,ProvisioningAdmission admission,
            @org.springframework.beans.factory.annotation.Value("${iam.integration.scim-local.authorized-routes:}") String routes) {
        return new ScimLocalExecutionService(
                execution, work, desiredState, transactions, scim, groups, properties, json,admission,ScimExecutionPolicy.configured(routes));
    }

    @Bean
    ScimLocalExecutionScheduler scimLocalExecutionScheduler(
            ScimLocalExecutionService service) {
        return new ScimLocalExecutionScheduler(service);
    }
}
