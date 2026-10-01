package io.wyrmgate.iam.api.governance;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.api.security.ControlPlaneAuthProperties;
import io.wyrmgate.iam.audit.application.SecurityAuditPort;
import io.wyrmgate.iam.governance.application.AccessRequestCommandService;
import io.wyrmgate.iam.governance.application.AccessRequestRepository;
import io.wyrmgate.iam.governance.application.ApprovalCommandService;
import io.wyrmgate.iam.governance.application.ApprovalQueryService;
import io.wyrmgate.iam.governance.application.ReviewQueryService;
import io.wyrmgate.iam.governance.application.ReviewRepository;
import io.wyrmgate.iam.governance.application.ReviewService;
import io.wyrmgate.iam.platform.crypto.SigningKeyProvider;
import io.wyrmgate.iam.platform.id.IdGenerator;
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
@EnableConfigurationProperties(
        ControlPlaneAuthProperties.class)
class GovernanceApiConfiguration {

    @Bean
    GovernanceApiMutationService governanceApiMutationService(
            AccessRequestCommandService requests,
            AccessRequestRepository requestRepository,
            ApprovalCommandService approvals,
            ApprovalQueryService approvalQueries,
            JdbcIdempotencyRepository idempotency,
            TransactionExecutor transactions,
            SecurityAuditPort audit,
            IdGenerator ids) {
        return new GovernanceApiMutationService(
                requests,
                requestRepository,
                approvals,
                approvalQueries,
                idempotency,
                transactions,
                audit,
                ids);
    }

    @Bean
    GovernanceReviewApiMutationService governanceReviewApiMutationService(
            AdministrativeAuthorizationService authorization,
            ReviewService reviews,
            ReviewRepository reviewRepository,
            ReviewQueryService reviewQueries,
            JdbcIdempotencyRepository idempotency,
            TransactionExecutor transactions) {
        return new GovernanceReviewApiMutationService(
                authorization,
                reviews,
                reviewRepository,
                reviewQueries,
                idempotency,
                transactions);
    }

    @Bean
    GovernanceCursorCodec governanceCursorCodec(
            ObjectProvider<SigningKeyProvider> signingKeys,
            ControlPlaneAuthProperties authProperties,
            @Value("${iam.api.cursor.lifetime:PT15M}")
                    Duration lifetime) {
        SigningKeyProvider provider =
                signingKeys.getIfAvailable();
        if (authProperties.enabled()
                && provider == null) {
            throw new IllegalStateException(
                    "iam.signing.enabled=true and signing key material are required when control-plane authentication is enabled");
        }
        return new GovernanceCursorCodec(
                provider,
                lifetime,
                Clock.systemUTC());
    }
}
