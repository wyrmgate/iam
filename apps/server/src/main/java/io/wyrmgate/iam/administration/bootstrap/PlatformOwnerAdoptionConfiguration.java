package io.wyrmgate.iam.administration.bootstrap;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorityRepository;
import io.wyrmgate.iam.administration.application.FirstPartyInitialAdminAnchorQuery;
import io.wyrmgate.iam.administration.application.GovernedActorStatusQuery;
import io.wyrmgate.iam.administration.application.InitialAdminBootstrapRepository;
import io.wyrmgate.iam.administration.application.PlatformOwnerAdoptionFactSink;
import io.wyrmgate.iam.administration.application.PlatformOwnerAdoptionRepository;
import io.wyrmgate.iam.administration.application.PlatformOwnerAdoptionService;
import io.wyrmgate.iam.administration.application.SealedInstallationBootstrapQuery;
import io.wyrmgate.iam.administration.application.StandaloneTenantOwnerRecoveryRepository;
import io.wyrmgate.iam.administration.persistence.JdbcPlatformOwnerAdoptionFactSink;
import io.wyrmgate.iam.administration.persistence.JdbcPlatformOwnerAdoptionRepository;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit, disabled-by-default ADR-0049 adoption adapter for sealed legacy installations. */
@Configuration
@EnableConfigurationProperties(PlatformOwnerAdoptionProperties.class)
public class PlatformOwnerAdoptionConfiguration {

    @Bean
    PlatformOwnerAdoptionRepository platformOwnerAdoptionRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcPlatformOwnerAdoptionRepository(jdbcTemplate);
    }

    @Bean
    PlatformOwnerAdoptionFactSink platformOwnerAdoptionFactSink(
            JdbcOutboxRepository outbox,
            IdGenerator ids) {
        return new JdbcPlatformOwnerAdoptionFactSink(outbox, ids);
    }

    @Bean
    PlatformOwnerAdoptionService platformOwnerAdoptionService(
            InitialAdminBootstrapRepository bootstrapRepository,
            AdministrativeAuthorityRepository authorityRepository,
            StandaloneTenantOwnerRecoveryRepository historicalEvidenceRepository,
            PlatformOwnerAdoptionRepository adoptionRepository,
            SealedInstallationBootstrapQuery sealedInstallation,
            GovernedActorStatusQuery governedActors,
            FirstPartyInitialAdminAnchorQuery firstPartyAnchors,
            PlatformOwnerAdoptionFactSink facts,
            IdGenerator ids,
            TransactionExecutor transactions) {
        return new PlatformOwnerAdoptionService(
                bootstrapRepository,
                authorityRepository,
                historicalEvidenceRepository,
                adoptionRepository,
                sealedInstallation,
                governedActors,
                firstPartyAnchors,
                facts,
                ids,
                transactions);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "iam.bootstrap.platform-owner-adoption",
            name = "enabled",
            havingValue = "true")
    ApplicationRunner platformOwnerAdoptionRunner(
            PlatformOwnerAdoptionProperties properties,
            PlatformOwnerAdoptionService service,
            IdGenerator ids) {
        return args -> service.adopt(
                new TenantContext(requireUuid(properties.tenantId(), "tenant-id")),
                requireUuid(properties.identityId(), "identity-id"),
                requireUuid(properties.principalId(), "principal-id"),
                requireUuid(properties.bootstrapCorrelationId(), "bootstrap-correlation-id"),
                Instant.now(),
                ids.nextId());
    }

    private static UUID requireUuid(String value, String name) {
        try {
            return UUID.fromString(requireText(value, name));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "iam.bootstrap.platform-owner-adoption." + name + " must be a UUID",
                    exception);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "iam.bootstrap.platform-owner-adoption." + name + " is required");
        }
        return value.trim();
    }
}
