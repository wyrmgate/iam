package io.wyrmgate.iam.identity.persistence;

import io.wyrmgate.iam.identity.application.IdentityCommandService;
import io.wyrmgate.iam.identity.application.IdentityFactSink;
import io.wyrmgate.iam.identity.application.IdentityRepository;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Spring composition for Identity persistence adapters and application commands. */
@Configuration
public class IdentityPersistenceConfiguration {

    @Bean
    IdentityRepository identityRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcIdentityRepository(jdbcTemplate);
    }

    @Bean
    IdentityFactSink identityFactSink(JdbcOutboxRepository outboxRepository, IdGenerator idGenerator) {
        return new JdbcIdentityFactSink(outboxRepository, idGenerator);
    }

    @Bean
    IdentityCommandService identityCommandService(
            IdentityRepository identityRepository,
            IdentityFactSink identityFactSink,
            IdGenerator idGenerator,
            TransactionExecutor transactionExecutor) {
        return new IdentityCommandService(
                identityRepository,
                identityFactSink,
                idGenerator,
                transactionExecutor);
    }
}
