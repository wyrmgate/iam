package io.wyrmgate.iam.api.integration;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.integration.application.IntegrationAdministrationCommandService;
import io.wyrmgate.iam.integration.application.IntegrationAdministrationRepository;
import io.wyrmgate.iam.integration.application.IntegrationEntitlementMappingRepository;
import io.wyrmgate.iam.integration.application.IntegrationEntitlementMappingService;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class IntegrationAdminApiConfiguration {

    @Bean
    IntegrationAdminApiMutationService integrationAdminApiMutationService(
            AdministrativeAuthorizationService authorization,
            IntegrationAdministrationCommandService commands,
            IntegrationAdministrationRepository repository,
            IntegrationEntitlementMappingService mappingCommands,
            IntegrationEntitlementMappingRepository mappings,
            JdbcIdempotencyRepository idempotency,
            TransactionExecutor transactions) {
        return new IntegrationAdminApiMutationService(
                authorization,commands,repository,mappingCommands,mappings,
                idempotency,transactions);
    }
}
