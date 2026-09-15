package io.wyrmgate.iam.administration.persistence;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationRepository;
import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.ControlPlaneActorBindingRepository;
import io.wyrmgate.iam.administration.application.ControlPlaneActorResolver;
import io.wyrmgate.iam.administration.application.GovernedActorStatusQuery;
import io.wyrmgate.iam.administration.application.InitialAdminBootstrapRepository;
import io.wyrmgate.iam.administration.application.InitialAdminBootstrapService;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Spring composition for Administration authorization, actor resolution and bootstrap persistence. */
@Configuration
public class AdministrationPersistenceConfiguration {

    @Bean
    AdministrativeAuthorizationRepository administrativeAuthorizationRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcAdministrativeAuthorizationRepository(jdbcTemplate);
    }

    @Bean
    AdministrativeAuthorizationService administrativeAuthorizationService(
            AdministrativeAuthorizationRepository repository,
            GovernedActorStatusQuery governedActorStatusQuery) {
        return new AdministrativeAuthorizationService(repository, governedActorStatusQuery);
    }

    @Bean
    ControlPlaneActorBindingRepository controlPlaneActorBindingRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcControlPlaneActorBindingRepository(jdbcTemplate);
    }

    @Bean
    ControlPlaneActorResolver controlPlaneActorResolver(ControlPlaneActorBindingRepository repository) {
        return new ControlPlaneActorResolver(repository);
    }

    @Bean
    InitialAdminBootstrapRepository initialAdminBootstrapRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcInitialAdminBootstrapRepository(jdbcTemplate);
    }

    @Bean
    InitialAdminBootstrapService initialAdminBootstrapService(
            InitialAdminBootstrapRepository bootstrapRepository,
            ControlPlaneActorBindingRepository actorBindingRepository,
            GovernedActorStatusQuery governedActorStatusQuery,
            IdGenerator idGenerator,
            TransactionExecutor transactionExecutor) {
        return new InitialAdminBootstrapService(
                bootstrapRepository,
                actorBindingRepository,
                governedActorStatusQuery,
                idGenerator,
                transactionExecutor);
    }
}
