package io.wyrmgate.iam.identity.persistence;

import io.wyrmgate.iam.identity.application.IdentityCommandService;
import io.wyrmgate.iam.identity.application.IdentityFactSink;
import io.wyrmgate.iam.identity.application.IdentityRepository;
import io.wyrmgate.iam.identity.application.SourceCorrelationFactSink;
import io.wyrmgate.iam.identity.application.SourceCorrelationRepository;
import io.wyrmgate.iam.identity.application.SourceCorrelationService;
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

    @Bean
    SourceCorrelationRepository sourceCorrelationRepository(JdbcTemplate jdbcTemplate, IdGenerator idGenerator) {
        return new JdbcSourceCorrelationRepository(jdbcTemplate, idGenerator);
    }

    @Bean
    SourceCorrelationFactSink sourceCorrelationFactSink(
            JdbcOutboxRepository outboxRepository,
            IdGenerator idGenerator) {
        return new JdbcSourceCorrelationFactSink(outboxRepository, idGenerator);
    }

    @Bean
    SourceCorrelationService sourceCorrelationService(
            SourceCorrelationRepository sourceCorrelationRepository,
            IdentityRepository identityRepository,
            SourceCorrelationFactSink sourceCorrelationFactSink,
            IdGenerator idGenerator,
            TransactionExecutor transactionExecutor) {
        return new SourceCorrelationService(
                sourceCorrelationRepository,
                identityRepository,
                sourceCorrelationFactSink,
                idGenerator,
                transactionExecutor);
    }
}
