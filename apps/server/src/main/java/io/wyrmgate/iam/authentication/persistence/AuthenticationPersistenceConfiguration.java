package io.wyrmgate.iam.authentication.persistence;

import io.wyrmgate.iam.authentication.application.AuthenticationRepository;
import io.wyrmgate.iam.authentication.application.AuthenticationService;
import io.wyrmgate.iam.authentication.application.AuthenticationSubjectQuery;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Spring composition for Authentication-owned persistence and application services. */
@Configuration
public class AuthenticationPersistenceConfiguration {

    @Bean
    AuthenticationRepository authenticationRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcAuthenticationRepository(jdbcTemplate);
    }

    @Bean
    AuthenticationService authenticationService(
            AuthenticationRepository repository,
            AuthenticationSubjectQuery subjects,
            IdGenerator ids,
            TransactionExecutor transactions) {
        return new AuthenticationService(repository, subjects, ids, transactions);
    }
}