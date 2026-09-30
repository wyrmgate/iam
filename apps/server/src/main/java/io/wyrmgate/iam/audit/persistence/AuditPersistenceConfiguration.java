package io.wyrmgate.iam.audit.persistence;

import io.wyrmgate.iam.audit.application.AuditCommandService;
import io.wyrmgate.iam.audit.application.AuditQueryService;
import io.wyrmgate.iam.audit.application.AuditRecordRepository;
import io.wyrmgate.iam.audit.application.SecurityAuditPort;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class AuditPersistenceConfiguration {

    @Bean
    AuditRecordRepository auditRecordRepository(JdbcTemplate jdbc) {
        return new JdbcAuditRecordRepository(jdbc);
    }

    @Bean
    SecurityAuditPort securityAuditPort(
            AuditRecordRepository repository,
            TransactionExecutor transactions) {
        return new AuditCommandService(repository, transactions, Clock.systemUTC());
    }

    @Bean
    AuditQueryService auditQueryService(AuditRecordRepository repository) {
        return new AuditQueryService(repository);
    }
}
