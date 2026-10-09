package io.wyrmgate.iam.idp.session;

import io.wyrmgate.iam.credential.application.CredentialAuthenticationService;
import io.wyrmgate.iam.identity.application.IdentityAuthenticationQuery;
import io.wyrmgate.iam.platform.id.IdGenerator;
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
    IdpBrowserSessionService idpBrowserSessionService(
            IdpBrowserSessionRepository sessions,
            IdentityAuthenticationQuery identities,
            CredentialAuthenticationService credentials,
            IdpSessionTokenCodec tokens,
            IdGenerator ids) {
        return new IdpBrowserSessionService(
                sessions,
                identities,
                credentials,
                tokens,
                ids);
    }
}
