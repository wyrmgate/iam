package io.wyrmgate.iam.api.identity;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.identity.application.IdentityCommandService;
import io.wyrmgate.iam.identity.application.IdentityRepository;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class IdentityApiConfiguration {

    @Bean
    IdentityApiMutationService identityApiMutationService(
            AdministrativeAuthorizationService authorization,
            IdentityCommandService commands,
            IdentityRepository identities,
            JdbcIdempotencyRepository idempotency,
            TransactionExecutor transactions) {
        return new IdentityApiMutationService(
                authorization, commands, identities, idempotency, transactions);
    }
}
