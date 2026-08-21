package io.wyrmgate.iam.platform.persistence;

import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Spring wiring for narrow Platform persistence adapters. */
@Configuration
public class PersistenceFoundationConfiguration {

    @Bean
    IdGenerator idGenerator() {
        return new UuidV7Generator();
    }

    @Bean
    TransactionExecutor transactionExecutor(PlatformTransactionManager transactionManager) {
        return new SpringTransactionExecutor(transactionManager);
    }

    @Bean
    JdbcTenantRepository tenantRepository(JdbcTemplate jdbcTemplate, IdGenerator idGenerator) {
        return new JdbcTenantRepository(jdbcTemplate, idGenerator);
    }

    @Bean
    JdbcOutboxRepository outboxRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcOutboxRepository(jdbcTemplate);
    }

    @Bean
    JdbcInboxRepository inboxRepository(JdbcTemplate jdbcTemplate, IdGenerator idGenerator) {
        return new JdbcInboxRepository(jdbcTemplate, idGenerator);
    }

    @Bean
    JdbcIdempotencyRepository idempotencyRepository(JdbcTemplate jdbcTemplate, IdGenerator idGenerator) {
        return new JdbcIdempotencyRepository(jdbcTemplate, idGenerator);
    }

    @Bean
    JdbcScheduledWorkRepository scheduledWorkRepository(JdbcTemplate jdbcTemplate, IdGenerator idGenerator) {
        return new JdbcScheduledWorkRepository(jdbcTemplate, idGenerator);
    }
}
