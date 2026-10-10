package io.wyrmgate.iam.idp.protocol;

import io.wyrmgate.iam.access.application.EffectiveAccessQuery;
import io.wyrmgate.iam.catalog.application.CatalogQueryService;
import io.wyrmgate.iam.catalog.application.SsoClientProtocolQuery;
import io.wyrmgate.iam.idp.session.IdpBrowserSessionService;
import io.wyrmgate.iam.platform.crypto.SigningKeyProvider;
import io.wyrmgate.iam.platform.id.IdGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@ConditionalOnProperty(prefix = "iam.idp", name = "enabled", havingValue = "true")
public class IdpProtocolRuntimeConfiguration {

    @Bean
    IdpAuthorizationCodeRepository idpAuthorizationCodeRepository(JdbcTemplate jdbc) {
        return new JdbcIdpAuthorizationCodeRepository(jdbc);
    }

    @Bean
    IdpApplicationAccessQuery idpApplicationAccessEvaluator(
            CatalogQueryService catalog,
            EffectiveAccessQuery effectiveAccess) {
        return new IdpApplicationAccessEvaluator(catalog, effectiveAccess);
    }

    @Bean
    IdpTokenIssuer idpJwtIssuer(
            IdpProtocolProperties properties,
            SigningKeyProvider signingKeys,
            JsonMapper objectMapper) {
        return new IdpJwtIssuer(properties, signingKeys, objectMapper);
    }

    @Bean
    IdpProtocolService idpProtocolService(
            SsoClientProtocolQuery clients,
            IdpBrowserSessionService sessions,
            IdpApplicationAccessQuery applicationAccess,
            IdpAuthorizationCodeRepository authorizationCodes,
            IdpTokenIssuer tokens,
            IdGenerator ids) {
        return new IdpProtocolService(
                clients, sessions, applicationAccess, authorizationCodes, tokens, ids);
    }
}
