package io.wyrmgate.iam.identity.persistence;

import io.wyrmgate.iam.identity.application.IdentityAuthenticationQuery;
import io.wyrmgate.iam.identity.application.IdentityAuthenticationQueryService;
import io.wyrmgate.iam.identity.application.IdentityRepository;
import io.wyrmgate.iam.identity.application.PrincipalRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition for Identity-owned first-party authentication queries. */
@Configuration
public class IdentityAuthenticationConfiguration {

    @Bean
    IdentityAuthenticationQuery identityAuthenticationQuery(
            PrincipalRepository principals,
            IdentityRepository identities) {
        return new IdentityAuthenticationQueryService(principals, identities);
    }
}
