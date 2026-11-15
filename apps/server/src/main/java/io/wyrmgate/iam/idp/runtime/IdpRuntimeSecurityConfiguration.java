package io.wyrmgate.iam.idp.runtime;

import io.wyrmgate.iam.audit.application.SecurityAuditPort;
import io.wyrmgate.iam.platform.id.IdGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class IdpRuntimeSecurityConfiguration {
    @Bean IdpAttemptLimiter idpAttemptLimiter(JdbcTemplate jdbc) { return new JdbcIdpAttemptLimiter(jdbc); }
    @Bean IdpSecurityEvidence idpSecurityEvidence(SecurityAuditPort audit, IdGenerator ids) {
        return new IdpSecurityEvidence(audit, ids);
    }
    @Bean
    @ConditionalOnProperty(prefix = "iam.idp", name = "enabled", havingValue = "true")
    IdpRuntimeCleanup idpRuntimeCleanup(JdbcTemplate jdbc) { return new IdpRuntimeCleanup(jdbc); }
}
