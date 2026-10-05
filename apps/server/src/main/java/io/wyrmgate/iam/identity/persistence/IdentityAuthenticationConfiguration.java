package io.wyrmgate.iam.identity.persistence;

import io.wyrmgate.iam.authentication.application.AuthenticationSubjectQuery;
import io.wyrmgate.iam.identity.application.IdentityRepository;
import io.wyrmgate.iam.identity.application.PrincipalRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Identity-owned composition for the narrow Authentication consumer contract. */
@Configuration
public class IdentityAuthenticationConfiguration {

    @Bean
    AuthenticationSubjectQuery authenticationSubjectQuery(
            PrincipalRepository principalRepository,
            IdentityRepository identityRepository) {
        return new IdentityAuthenticationSubjectQuery(principalRepository, identityRepository);
    }
}