package io.wyrmgate.iam.integration.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.integration.application.ConnectorWorkerProtocolProperties;
import io.wyrmgate.iam.integration.application.ConnectorWorkerProtocolService;
import io.wyrmgate.iam.integration.application.DesiredStateRevisionQuery;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@EnableConfigurationProperties(ConnectorWorkerProtocolProperties.class)
public class IntegrationRuntimeConfiguration {

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    ObjectMapper integrationObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    JdbcIntegrationRuntimeRepository jdbcIntegrationRuntimeRepository(
            JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, IdGenerator idGenerator) {
        return new JdbcIntegrationRuntimeRepository(jdbcTemplate, objectMapper, idGenerator);
    }

    @Bean
    ConnectorWorkerProtocolService connectorWorkerProtocolService(
            JdbcIntegrationRuntimeRepository workers,
            ObjectProvider<DesiredStateRevisionQuery> desiredStateQuery,
            TransactionExecutor transactions,
            ConnectorWorkerProtocolProperties properties,
            ObjectMapper objectMapper) {
        return new ConnectorWorkerProtocolService(
                workers,
                workers,
                desiredStateQuery.getIfAvailable(DesiredStateRevisionQuery::unavailable),
                transactions,
                properties,
                objectMapper);
    }
}
