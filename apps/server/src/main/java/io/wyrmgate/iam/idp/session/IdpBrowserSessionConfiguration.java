package io.wyrmgate.iam.idp.session;

import io.wyrmgate.iam.idp.runtime.IdpAttemptLimiter;
import io.wyrmgate.iam.idp.federation.FederatedSubjectResolver;
import io.wyrmgate.iam.integration.application.federation.FederationTrustQuery;
import io.wyrmgate.iam.idp.runtime.IdpSecurityEvidence;
import io.wyrmgate.iam.catalog.application.SsoClientProtocolQuery;
import io.wyrmgate.iam.credential.application.CredentialAuthenticationService;
import io.wyrmgate.iam.identity.application.IdentityAuthenticationQuery;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.tenancy.application.TenantOverviewQuery;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class IdpBrowserSessionConfiguration {

    @Bean
    IdpBrowserSessionRepository idpBrowserSessionRepository(JdbcTemplate jdbc) {
        return new JdbcIdpBrowserSessionRepository(jdbc);
    }

    @Bean
    IdpSessionTokenCodec idpSessionTokenCodec() {
        return new IdpSessionTokenCodec();
    }

    @Bean
    IdpCsrfTokenCodec idpCsrfTokenCodec() {
        return new IdpCsrfTokenCodec();
    }

    @Bean
    FederatedSubjectResolver federatedSubjectResolver(FederationTrustQuery trust,IdentityAuthenticationQuery identities,TenantOverviewQuery tenants){
        return new FederatedSubjectResolver(trust,identities,tenants);
    }

    @Bean
    IdpBrowserSessionService idpBrowserSessionService(
            IdpBrowserSessionRepository sessions,
            IdentityAuthenticationQuery identities,
            TenantOverviewQuery tenants,
            CredentialAuthenticationService credentials,
            IdpSessionTokenCodec tokens,
            IdGenerator ids, IdpSecurityEvidence evidence,FederatedSubjectResolver federation) {
        return new IdpBrowserSessionService(
                sessions,
                identities,
                tenants,
                credentials,
                tokens,
                ids, evidence,federation);
    }

    @Bean
    IdpInteractiveLoginService idpInteractiveLoginService(
            SsoClientProtocolQuery clients,
            IdentityAuthenticationQuery identities,
            CredentialAuthenticationService credentials,
            IdpBrowserSessionService sessions, IdpAttemptLimiter limiter, IdpSecurityEvidence evidence, IdGenerator ids) {
        return new IdpInteractiveLoginService(clients, identities, credentials, sessions, limiter, evidence, ids);
    }
}
