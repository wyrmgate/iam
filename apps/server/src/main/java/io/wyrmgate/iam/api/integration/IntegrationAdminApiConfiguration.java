package io.wyrmgate.iam.api.integration;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.integration.application.IntegrationAdministrationCommandService;
import io.wyrmgate.iam.integration.application.IntegrationAdministrationRepository;
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
            JdbcIdempotencyRepository idempotency,
            TransactionExecutor transactions) {
        return new IntegrationAdminApiMutationService(
                authorization,commands,repository,idempotency,transactions);
    }
}
