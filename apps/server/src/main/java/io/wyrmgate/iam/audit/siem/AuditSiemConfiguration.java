package io.wyrmgate.iam.audit.siem;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.audit.application.AuditRecordRepository;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "iam.audit.siem.enabled", havingValue = "true")
class AuditSiemConfiguration {

    @Bean
    AuditSiemPublisher auditSiemPublisher(
            ObjectMapper json,
            @Value("${iam.audit.siem.endpoint}") String endpoint,
            @Value("${iam.audit.siem.secret-base64}") String secretBase64,
            @Value("${iam.audit.siem.connect-timeout:PT5S}") Duration connectTimeout,
            @Value("${iam.audit.siem.request-timeout:PT10S}") Duration requestTimeout) {
        byte[] secret;
        try {
            secret = Base64.getDecoder().decode(secretBase64);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("iam.audit.siem.secret-base64 must be valid Base64", invalid);
        }
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new SignedHttpsAuditSiemPublisher(
                client,
                URI.create(endpoint),
                requestTimeout,
                secret,
                json);
    }

    @Bean
    AuditSiemDeliveryService auditSiemDeliveryService(
            JdbcScheduledWorkRepository scheduledWork,
            AuditRecordRepository records,
            AuditSiemPublisher publisher,
            @Value("${iam.audit.siem.claim-lease:PT30S}") Duration claimLease,
            @Value("${iam.audit.siem.batch-size:50}") int batchSize,
            @Value("${iam.audit.siem.max-attempts:8}") int maxAttempts,
            @Value("${iam.audit.siem.retry-delay:PT5S}") Duration retryDelay) {
        return new AuditSiemDeliveryService(
                scheduledWork,
                records,
                publisher,
                claimLease,
                batchSize,
                maxAttempts,
                retryDelay);
    }

    @Bean
    AuditSiemScheduler auditSiemScheduler(AuditSiemDeliveryService service) {
        return new AuditSiemScheduler(service);
    }
}
