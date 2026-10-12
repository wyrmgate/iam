package io.wyrmgate.iam.api.integration;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.integration.application.IntegrationAdministrationCommandService;
import io.wyrmgate.iam.integration.application.IntegrationAdministrationRepository;
import io.wyrmgate.iam.integration.application.IntegrationEntitlementMappingRepository;
import io.wyrmgate.iam.integration.application.IntegrationEntitlementMappingService;
import io.wyrmgate.iam.api.security.ControlPlaneAuthProperties;
import io.wyrmgate.iam.audit.application.SecurityAuditPort;
import io.wyrmgate.iam.platform.crypto.SigningKeyProvider;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class IntegrationAdminApiConfiguration {

    @Bean
    IntegrationAdminApiMutationService integrationAdminApiMutationService(
            AdministrativeAuthorizationService authorization,
            IntegrationAdministrationCommandService commands,
            IntegrationAdministrationRepository repository,
            IntegrationEntitlementMappingService mappingCommands,
            IntegrationEntitlementMappingRepository mappings,
            JdbcIdempotencyRepository idempotency,
            TransactionExecutor transactions,
            SecurityAuditPort audit,
            IdGenerator ids) {
        return new IntegrationAdminApiMutationService(
                authorization,commands,repository,mappingCommands,mappings,
                idempotency,transactions,audit,ids);
    }

    @Bean
    IntegrationOperationalCursorCodec integrationOperationalCursorCodec(
            ObjectProvider<SigningKeyProvider> signingKeys,
            ControlPlaneAuthProperties authProperties,
            @Value("${iam.api.cursor.lifetime:PT15M}") Duration lifetime) {
        SigningKeyProvider provider = signingKeys.getIfAvailable();
        if (authProperties.enabled() && provider == null) {
            throw new IllegalStateException(
                    "iam.signing.enabled=true and signing key material are required when control-plane authentication is enabled");
        }
        return new IntegrationOperationalCursorCodec(provider, lifetime, Clock.systemUTC());
    }
}
