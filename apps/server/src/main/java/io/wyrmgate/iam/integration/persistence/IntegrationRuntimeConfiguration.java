package io.wyrmgate.iam.integration.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.integration.application.ConnectorWorkRepository;
import io.wyrmgate.iam.integration.application.ConnectorWorkerProtocolProperties;
import io.wyrmgate.iam.integration.application.ConnectorWorkerProtocolService;
import io.wyrmgate.iam.integration.application.DesiredStateRevisionQuery;
import io.wyrmgate.iam.integration.application.WorkerRegistrationRepository;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@EnableConfigurationProperties(ConnectorWorkerProtocolProperties.class)
public class IntegrationRuntimeConfiguration {

    @Bean
    JdbcIntegrationRuntimeRepository jdbcIntegrationRuntimeRepository(
            JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, IdGenerator idGenerator) {
        return new JdbcIntegrationRuntimeRepository(jdbcTemplate, objectMapper, idGenerator);
    }

    @Bean
    WorkerRegistrationRepository workerRegistrationRepository(JdbcIntegrationRuntimeRepository repository) {
        return repository;
    }

    @Bean
    ConnectorWorkRepository connectorWorkRepository(JdbcIntegrationRuntimeRepository repository) {
        return repository;
    }

    @Bean
    ConnectorWorkerProtocolService connectorWorkerProtocolService(
            WorkerRegistrationRepository workers,
            ConnectorWorkRepository work,
            ObjectProvider<DesiredStateRevisionQuery> desiredStateQuery,
            TransactionExecutor transactions,
            ConnectorWorkerProtocolProperties properties,
            ObjectMapper objectMapper) {
        return new ConnectorWorkerProtocolService(
                workers,
                work,
                desiredStateQuery.getIfAvailable(DesiredStateRevisionQuery::unavailable),
                transactions,
                properties,
                objectMapper);
    }
}
