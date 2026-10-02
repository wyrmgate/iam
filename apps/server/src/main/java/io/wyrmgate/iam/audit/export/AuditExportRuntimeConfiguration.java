package io.wyrmgate.iam.audit.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.audit.application.AuditExportArtifactStore;
import io.wyrmgate.iam.audit.application.AuditExportRepository;
import io.wyrmgate.iam.audit.application.AuditExportService;
import io.wyrmgate.iam.audit.application.AuditRetentionPolicyRepository;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class AuditExportRuntimeConfiguration {

    @Bean
    AuditExportArtifactStore auditExportArtifactStore(
            @Value("${iam.audit.export.directory:./var/audit-exports}") String directory) {
        return new FileSystemAuditExportArtifactStore(Path.of(directory));
    }

    @Bean
    AuditExportService auditExportService(
            AuditExportRepository repository,
            AuditRetentionPolicyRepository retentionPolicies,
            AuditExportArtifactStore artifactStore,
            JdbcIdempotencyRepository idempotency,
            JdbcScheduledWorkRepository scheduledWork,
            TransactionExecutor transactions,
            IdGenerator ids,
            ObjectMapper objectMapper,
            @Value("${iam.audit.export.enabled:false}") boolean enabled,
            @Value("${iam.audit.export.claim-lease:PT30S}") Duration claimLease,
            @Value("${iam.audit.export.batch-size:10}") int batchSize,
            @Value("${iam.audit.export.max-attempts:5}") int maxAttempts,
            @Value("${iam.audit.export.retry-delay:PT5S}") Duration retryDelay,
            @Value("${iam.audit.export.artifact-retention:}") String artifactRetention) {
        Duration retention = artifactRetention == null || artifactRetention.isBlank()
                ? null
                : Duration.parse(artifactRetention);
        return new AuditExportService(
                repository,
                retentionPolicies,
                artifactStore,
                idempotency,
                scheduledWork,
                transactions,
                ids,
                objectMapper,
                enabled,
                claimLease,
                batchSize,
                maxAttempts,
                retryDelay,
                retention);
    }

    @Bean
    AuditExportScheduler auditExportScheduler(AuditExportService service) {
        return new AuditExportScheduler(service);
    }
}
