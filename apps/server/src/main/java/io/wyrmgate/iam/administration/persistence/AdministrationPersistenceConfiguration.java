package io.wyrmgate.iam.administration.persistence;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationRepository;
import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.GovernedActorStatusQuery;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Spring composition for the Administration authorization foundation. */
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
}
