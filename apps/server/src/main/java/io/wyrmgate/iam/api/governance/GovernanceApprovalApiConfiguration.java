package io.wyrmgate.iam.api.governance;

import io.wyrmgate.iam.api.security.ControlPlaneAuthProperties;
import io.wyrmgate.iam.governance.application.ApprovalQueryService;
import io.wyrmgate.iam.governance.application.ApprovalService;
import io.wyrmgate.iam.platform.crypto.SigningKeyProvider;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ControlPlaneAuthProperties.class)
class GovernanceApprovalApiConfiguration {

    @Bean
    GovernanceApprovalApiMutationService
            governanceApprovalApiMutationService(
                    ApprovalService approvals,
                    ApprovalQueryService queries,
                    JdbcIdempotencyRepository idempotency,
                    TransactionExecutor transactions) {
        return new GovernanceApprovalApiMutationService(
                approvals, queries, idempotency, transactions);
    }

    @Bean
    GovernanceApprovalCursorCodec
            governanceApprovalCursorCodec(
                    ObjectProvider<SigningKeyProvider> signingKeys,
                    ControlPlaneAuthProperties authProperties,
                    @Value("${iam.api.cursor.lifetime:PT15M}")
                            Duration lifetime) {
        SigningKeyProvider provider =
                signingKeys.getIfAvailable();
        if (authProperties.enabled() && provider == null) {
            throw new IllegalStateException(
                    "iam.signing.enabled=true and signing key material are required when control-plane authentication is enabled");
        }
        return new GovernanceApprovalCursorCodec(
                provider, lifetime, Clock.systemUTC());
    }
}
