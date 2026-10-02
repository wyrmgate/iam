package io.wyrmgate.iam.audit.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.audit.application.AuditArchiveRepository;
import io.wyrmgate.iam.audit.application.AuditArchiveService;
import io.wyrmgate.iam.audit.application.AuditExportArtifactStore;
import io.wyrmgate.iam.audit.application.AuditRetentionPolicyRepository;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class AuditArchiveRuntimeConfiguration {

    @Bean
    AuditArchiveService auditArchiveService(
            AuditArchiveRepository archives,
            AuditRetentionPolicyRepository policies,
            AuditExportArtifactStore artifactStore,
            JdbcScheduledWorkRepository scheduledWork,
            TransactionExecutor transactions,
            IdGenerator ids,
            ObjectMapper objectMapper,
            @Value("${iam.audit.archive.enabled:false}") boolean enabled,
            @Value("${iam.audit.archive.claim-lease:PT30S}") Duration claimLease,
            @Value("${iam.audit.archive.batch-size:10}") int batchSize,
            @Value("${iam.audit.archive.max-attempts:5}") int maxAttempts,
            @Value("${iam.audit.archive.retry-delay:PT5S}") Duration retryDelay) {
        return new AuditArchiveService(
                archives,
                policies,
                artifactStore,
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

    @Bean
    ApplicationRunner auditRetentionPolicyBootstrap(
            AuditArchiveService service,
            @Value("${iam.audit.retention.version:}") String version,
            @Value("${iam.audit.retention.export-artifact-retention:}") String exportArtifactRetention,
            @Value("${iam.audit.retention.archive-eligible-after:}") String archiveEligibleAfter,
            @Value("${iam.audit.retention.minimum-online-retention:}") String minimumOnlineRetention,
            @Value("${iam.audit.retention.minimum-archive-retention:}") String minimumArchiveRetention,
            @Value("${iam.audit.retention.effective-from:}") String effectiveFrom,
            @Value("${iam.audit.retention.tenant-id:}") String tenantId) {
        return args -> {
            if (allBlank(
                    version,
                    exportArtifactRetention,
                    archiveEligibleAfter,
                    minimumOnlineRetention,
                    minimumArchiveRetention,
                    effectiveFrom,
                    tenantId)) {
                return;
            }
            requireConfigured("version", version);
            requireConfigured("export-artifact-retention", exportArtifactRetention);
            requireConfigured("archive-eligible-after", archiveEligibleAfter);
            requireConfigured("minimum-online-retention", minimumOnlineRetention);
            requireConfigured("minimum-archive-retention", minimumArchiveRetention);
            requireConfigured("effective-from", effectiveFrom);
            requireConfigured("tenant-id", tenantId);
            service.registerPolicy(
                    new io.wyrmgate.iam.platform.tenant.TenantContext(java.util.UUID.fromString(tenantId)),
                    Long.parseLong(version),
                    Duration.parse(exportArtifactRetention),
                    Duration.parse(archiveEligibleAfter),
                    Duration.parse(minimumOnlineRetention),
                    Duration.parse(minimumArchiveRetention),
                    Instant.parse(effectiveFrom));
        };
    }

    private static boolean allBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return false;
        }
        return true;
    }

    private static void requireConfigured(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "iam.audit.retention." + name + " is required when retention bootstrap is configured");
        }
    }
}
