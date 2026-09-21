package io.wyrmgate.iam.integration.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.integration.application.ConnectorWorkerProtocolProperties;
import io.wyrmgate.iam.integration.application.ConnectorWorkerProtocolService;
import io.wyrmgate.iam.integration.application.IntegrationAdministrationCommandService;
import io.wyrmgate.iam.integration.application.IntegrationAdministrationFactSink;
import io.wyrmgate.iam.integration.application.IntegrationAdministrationRepository;
import io.wyrmgate.iam.integration.application.IntegrationEntitlementMappingRepository;
import io.wyrmgate.iam.integration.application.IntegrationEntitlementMappingService;
import io.wyrmgate.iam.integration.application.IntegrationObservedAccessQuery;
import io.wyrmgate.iam.catalog.application.CatalogEntitlementReferenceQuery;
import io.wyrmgate.iam.access.application.DesiredAccessStateQuery;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
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
    IntegrationAdministrationRepository integrationAdministrationRepository(
            JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        return new JdbcIntegrationAdministrationRepository(jdbcTemplate, objectMapper);
    }

    @Bean
    JdbcIntegrationObservationRepository jdbcIntegrationObservationRepository(
            JdbcTemplate jdbcTemplate) {
        return new JdbcIntegrationObservationRepository(jdbcTemplate);
    }

    @Bean
    IntegrationAdministrationFactSink integrationAdministrationFactSink(
            JdbcOutboxRepository outboxRepository,
            IdGenerator idGenerator) {
        return new JdbcIntegrationAdministrationFactSink(outboxRepository, idGenerator);
    }

    @Bean
    IntegrationAdministrationCommandService integrationAdministrationCommandService(
            IntegrationAdministrationRepository repository,
            IntegrationAdministrationFactSink factSink,
            IdGenerator idGenerator,
            TransactionExecutor transactions) {
        return new IntegrationAdministrationCommandService(
                repository, factSink, idGenerator, transactions);
    }

    @Bean
    IntegrationEntitlementMappingService integrationEntitlementMappingService(
            IntegrationAdministrationRepository administration,
            IntegrationEntitlementMappingRepository mappings,
            CatalogEntitlementReferenceQuery catalog,
            IntegrationAdministrationFactSink facts,
            IdGenerator ids,
            TransactionExecutor transactions) {
        return new IntegrationEntitlementMappingService(
                administration, mappings, catalog, facts, ids, transactions);
    }

    @Bean
    ConnectorWorkerProtocolService connectorWorkerProtocolService(
            JdbcIntegrationRuntimeRepository workers,
            DesiredAccessStateQuery desiredStateQuery,
            TransactionExecutor transactions,
            ConnectorWorkerProtocolProperties properties,
            ObjectMapper objectMapper) {
        return new ConnectorWorkerProtocolService(
                workers,
                workers,
                desiredStateQuery,
                transactions,
                properties,
                objectMapper);
    }
}
