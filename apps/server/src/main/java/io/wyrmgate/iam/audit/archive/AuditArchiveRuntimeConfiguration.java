package io.wyrmgate.iam.audit.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.audit.application.AuditArchiveRepository;
import io.wyrmgate.iam.audit.application.AuditArchiveService;
import io.wyrmgate.iam.audit.application.AuditRetentionPolicyRepository;
import io.wyrmgate.iam.audit.export.FileSystemAuditExportArtifactStore;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class AuditArchiveRuntimeConfiguration {

    @Bean
    AuditArchiveService auditArchiveService(
            AuditArchiveRepository repository,
            AuditRetentionPolicyRepository retentionPolicies,
            JdbcScheduledWorkRepository scheduledWork,
            TransactionExecutor transactions,
            IdGenerator ids,
            ObjectMapper objectMapper,
            @Value("${iam.audit.archive.directory:./var/audit-archives}") String directory,
            @Value("${iam.audit.archive.enabled:false}") boolean enabled,
            @Value("${iam.audit.archive.claim-lease:PT30S}") Duration claimLease,
            @Value("${iam.audit.archive.batch-size:10}") int batchSize,
            @Value("${iam.audit.archive.max-attempts:5}") int maxAttempts,
            @Value("${iam.audit.archive.retry-delay:PT5S}") Duration retryDelay) {
        return new AuditArchiveService(
                repository,
                retentionPolicies,
                new FileSystemAuditExportArtifactStore(Path.of(directory)),
                scheduledWork,
                transactions,
                ids,
                objectMapper,
                enabled,
                claimLease,
                batchSize,
                maxAttempts,
                retryDelay);
    }

    @Bean
    AuditArchiveScheduler auditArchiveScheduler(AuditArchiveService service) {
        return new AuditArchiveScheduler(service);
    }
}
