package io.wyrmgate.iam.integration.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.integration.application.ConnectorWorkerProtocolProperties;
import io.wyrmgate.iam.integration.application.ConnectorWorkerProtocolService;
import io.wyrmgate.iam.integration.application.IntegrationAdministrationCommandService;
import io.wyrmgate.iam.integration.application.IntegrationAdministrationFactSink;
import io.wyrmgate.iam.integration.application.IntegrationAdministrationRepository;
import io.wyrmgate.iam.integration.application.IntegrationEntitlementMappingRepository;
import io.wyrmgate.iam.integration.application.IntegrationEntitlementMappingService;
import io.wyrmgate.iam.integration.application.IntegrationObservedAccessFactSink;
import io.wyrmgate.iam.integration.application.IntegrationObservedAccessQuery;
import io.wyrmgate.iam.integration.application.GrantProvisioningPlannerService;
import io.wyrmgate.iam.integration.application.GrantProvisioningPlanningProcessor;
import io.wyrmgate.iam.integration.application.GrantProvisioningRepository;
import io.wyrmgate.iam.integration.application.IntegrationPrincipalProvisioningFactSink;
import io.wyrmgate.iam.integration.application.PrincipalProvisioningPlannerService;
import io.wyrmgate.iam.integration.application.PrincipalProvisioningPlanningProcessor;
import io.wyrmgate.iam.integration.application.PrincipalProvisioningRepository;
import io.wyrmgate.iam.catalog.application.CatalogEntitlementReferenceQuery;
import io.wyrmgate.iam.access.application.DesiredAccessStateQuery;
import io.wyrmgate.iam.access.application.DesiredProvisioningStateQuery;
import io.wyrmgate.iam.identity.application.PrincipalTechnicalReferenceQuery;
import io.wyrmgate.iam.identity.application.PrincipalProvisioningProfileQuery;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
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
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            IdGenerator idGenerator,
            IntegrationObservedAccessFactSink observedAccessFacts,
            IntegrationPrincipalProvisioningFactSink principalProvisioningFacts) {
        return new JdbcIntegrationRuntimeRepository(
                jdbcTemplate,
                objectMapper,
                idGenerator,
                observedAccessFacts,
                principalProvisioningFacts);
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
    IntegrationPrincipalProvisioningFactSink integrationPrincipalProvisioningFactSink(
            JdbcOutboxRepository outboxRepository,
            IdGenerator idGenerator,
            ObjectMapper objectMapper) {
        return new JdbcIntegrationPrincipalProvisioningFactSink(
                outboxRepository, idGenerator, objectMapper);
    }

    @Bean
    IntegrationObservedAccessFactSink integrationObservedAccessFactSink(
            JdbcOutboxRepository outboxRepository,
            IdGenerator idGenerator) {
        return new JdbcIntegrationObservedAccessFactSink(outboxRepository, idGenerator);
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
            IntegrationObservedAccessFactSink observedAccessFacts,
            IdGenerator ids,
            TransactionExecutor transactions) {
        return new IntegrationEntitlementMappingService(
                administration, mappings, catalog, facts, observedAccessFacts, ids, transactions);
    }

    @Bean
    PrincipalProvisioningRepository principalProvisioningRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            IdGenerator idGenerator) {
        return new JdbcPrincipalProvisioningRepository(
                jdbcTemplate, objectMapper, idGenerator);
    }

    @Bean
    PrincipalProvisioningPlannerService principalProvisioningPlannerService(
            DesiredProvisioningStateQuery desiredProvisioningStateQuery,
            PrincipalTechnicalReferenceQuery principalTechnicalReferenceQuery,
            PrincipalProvisioningProfileQuery principalProvisioningProfileQuery,
            PrincipalProvisioningRepository principalProvisioningRepository) {
        return new PrincipalProvisioningPlannerService(
                desiredProvisioningStateQuery,
                principalTechnicalReferenceQuery,
                principalProvisioningProfileQuery,
                principalProvisioningRepository);
    }

    @Bean
    PrincipalProvisioningPlanningProcessor principalProvisioningPlanningProcessor(
            JdbcOutboxRepository outboxRepository,
            PrincipalProvisioningPlannerService planner,
            TransactionExecutor transactions) {
        return new PrincipalProvisioningPlanningProcessor(
                outboxRepository, planner, transactions);
    }

    @Bean
    PrincipalProvisioningPlanningScheduler principalProvisioningPlanningScheduler(
            PrincipalProvisioningPlanningProcessor processor) {
        return new PrincipalProvisioningPlanningScheduler(processor);
    }

    @Bean
    GrantProvisioningRepository grantProvisioningRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            IdGenerator idGenerator) {
        return new JdbcGrantProvisioningRepository(
                jdbcTemplate, objectMapper, idGenerator);
    }

    @Bean
    GrantProvisioningPlannerService grantProvisioningPlannerService(
            DesiredProvisioningStateQuery desiredProvisioningStateQuery,
            PrincipalTechnicalReferenceQuery principalTechnicalReferenceQuery,
            GrantProvisioningRepository grantProvisioningRepository) {
        return new GrantProvisioningPlannerService(
                desiredProvisioningStateQuery,
                principalTechnicalReferenceQuery,
                grantProvisioningRepository);
    }

    @Bean
    GrantProvisioningPlanningProcessor grantProvisioningPlanningProcessor(
            JdbcOutboxRepository outboxRepository,
            GrantProvisioningPlannerService planner,
            TransactionExecutor transactions) {
        return new GrantProvisioningPlanningProcessor(
                outboxRepository, planner, transactions);
    }

    @Bean
    GrantProvisioningPlanningScheduler grantProvisioningPlanningScheduler(
            GrantProvisioningPlanningProcessor processor) {
        return new GrantProvisioningPlanningScheduler(processor);
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
