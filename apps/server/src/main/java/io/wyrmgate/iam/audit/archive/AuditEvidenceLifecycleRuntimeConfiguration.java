package io.wyrmgate.iam.audit.archive;

import io.wyrmgate.iam.audit.application.AuditArchiveRepository;
import io.wyrmgate.iam.audit.application.AuditEvidenceLifecycleRepository;
import io.wyrmgate.iam.audit.application.AuditEvidenceLifecycleService;
import io.wyrmgate.iam.audit.application.AuditExportArtifactStore;
import io.wyrmgate.iam.audit.application.AuditRetentionPolicyRepository;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class AuditEvidenceLifecycleRuntimeConfiguration {

    @Bean
    AuditEvidenceLifecycleService auditEvidenceLifecycleService(
            AuditEvidenceLifecycleRepository lifecycle,
            AuditArchiveRepository archives,
            AuditRetentionPolicyRepository policies,
            AuditExportArtifactStore artifactStore,
            JdbcIdempotencyRepository idempotency,
            JdbcScheduledWorkRepository scheduledWork,
            TransactionExecutor transactions,
            IdGenerator ids,
            @Value("${iam.audit.purge.enabled:false}") boolean purgeEnabled,
            @Value("${iam.audit.purge.claim-lease:PT30S}") Duration claimLease,
            @Value("${iam.audit.purge.batch-size:5}") int batchSize,
            @Value("${iam.audit.purge.max-attempts:3}") int maxAttempts,
            @Value("${iam.audit.purge.retry-delay:PT30S}") Duration retryDelay) {
        return new AuditEvidenceLifecycleService(
                lifecycle,
                archives,
                policies,
                artifactStore,
                idempotency,
                scheduledWork,
                transactions,
                ids,
                purgeEnabled,
                claimLease,
                batchSize,
                maxAttempts,
                retryDelay);
    }

    @Bean
    AuditPurgeScheduler auditPurgeScheduler(AuditEvidenceLifecycleService service) {
        return new AuditPurgeScheduler(service);
    }
}
