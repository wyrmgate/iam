package io.wyrmgate.iam.administration.bootstrap;

import io.wyrmgate.iam.administration.application.InitialAdminBootstrapService;
import io.wyrmgate.iam.administration.domain.ExternalAuthenticationSubject;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit one-shot adapter for provisioning a tenant's first administrative actor. */
@Configuration
@EnableConfigurationProperties(InitialAdminBootstrapProperties.class)
public class InitialAdminBootstrapConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "iam.bootstrap.initial-admin",
            name = "enabled",
            havingValue = "true")
    ApplicationRunner initialAdminBootstrapRunner(
            InitialAdminBootstrapProperties properties,
            InitialAdminBootstrapService bootstrapService,
            IdGenerator idGenerator) {
        return args -> bootstrapService.bootstrap(
                new TenantContext(requireUuid(properties.tenantId(), "tenant-id")),
                requireUuid(properties.identityId(), "identity-id"),
                new ExternalAuthenticationSubject(
                        requireText(properties.issuer(), "issuer"),
                        requireText(properties.subject(), "subject")),
                Instant.now(),
                idGenerator.nextId());
    }

    private static UUID requireUuid(String value, String name) {
        try {
            return UUID.fromString(requireText(value, name));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("iam.bootstrap.initial-admin." + name + " must be a UUID", exception);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("iam.bootstrap.initial-admin." + name + " is required");
        }
        return value;
    }
}
