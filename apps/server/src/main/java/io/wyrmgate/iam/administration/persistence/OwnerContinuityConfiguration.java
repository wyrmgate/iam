package io.wyrmgate.iam.administration.persistence;

import io.wyrmgate.iam.administration.application.PlatformOwnerAuthorityQuery;
import io.wyrmgate.iam.identity.application.PrincipalAdministrativeContinuityGuard;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Additional ADR-0049 owner-continuity composition kept separate from baseline Administration wiring. */
@Configuration
public class OwnerContinuityConfiguration {

    @Bean
    PrincipalAdministrativeContinuityGuard principalAdministrativeContinuityGuard(
            JdbcTemplate jdbcTemplate,
            PlatformOwnerAuthorityQuery platformOwnerAuthorityQuery) {
        return new JdbcPrincipalAdministrativeContinuityGuard(
                jdbcTemplate, platformOwnerAuthorityQuery);
    }
}
