package io.wyrmgate.iam.audit.persistence;

import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassAuditSink;
import io.wyrmgate.iam.audit.application.AuditAdministrativeBreakGlassAuditSink;
import io.wyrmgate.iam.audit.application.AuditCommandService;
import io.wyrmgate.iam.audit.application.AuditExportRepository;
import io.wyrmgate.iam.audit.application.AuditQueryService;
import io.wyrmgate.iam.audit.application.AuditRecordRepository;
import io.wyrmgate.iam.audit.application.SecurityAuditPort;
import io.wyrmgate.iam.platform.id.IdGenerator;
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
    AuditExportRepository auditExportRepository(JdbcTemplate jdbc) {
        return new JdbcAuditExportRepository(jdbc);
    }

    @Bean
    SecurityAuditPort securityAuditPort(
            AuditRecordRepository repository,
            TransactionExecutor transactions) {
        return new AuditCommandService(repository, transactions, Clock.systemUTC());
    }

    @Bean
    AdministrativeBreakGlassAuditSink administrativeBreakGlassAuditSink(
            SecurityAuditPort audit,
            IdGenerator ids) {
        return new AuditAdministrativeBreakGlassAuditSink(audit, ids);
    }

    @Bean
    AuditQueryService auditQueryService(AuditRecordRepository repository) {
        return new AuditQueryService(repository);
    }
}
