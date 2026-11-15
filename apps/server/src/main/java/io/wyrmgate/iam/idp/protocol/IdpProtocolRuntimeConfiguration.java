package io.wyrmgate.iam.idp.protocol;

import io.wyrmgate.iam.idp.runtime.IdpAttemptLimiter;
import io.wyrmgate.iam.idp.runtime.IdpSecurityEvidence;
import io.wyrmgate.iam.access.application.EffectiveAccessQuery;
import io.wyrmgate.iam.catalog.application.CatalogQueryService;
import io.wyrmgate.iam.catalog.application.SsoClientProtocolQuery;
import io.wyrmgate.iam.idp.session.IdpBrowserSessionService;
import io.wyrmgate.iam.idp.session.IdpInteractiveLoginService;
import io.wyrmgate.iam.idp.session.IdpSessionTokenCodec;
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
    IdpLoginInteractionRepository idpLoginInteractionRepository(JdbcTemplate jdbc) {
        return new JdbcIdpLoginInteractionRepository(jdbc);
    }

    @Bean
    IdpLoginInteractionService idpLoginInteractionService(IdpProtocolService protocol,
            SsoClientProtocolQuery clients, IdpInteractiveLoginService login, IdpBrowserSessionService sessions,
            IdpSessionTokenCodec tokens, IdpLoginInteractionRepository interactions, IdpAttemptLimiter limiter) {
        return new IdpLoginInteractionService(protocol, clients, login, sessions, tokens, interactions, limiter);
    }

    @Bean
    JdbcIdpFederationInteraction idpFederationInteraction(JdbcTemplate jdbc){return new JdbcIdpFederationInteraction(jdbc);}
    @Bean
    io.wyrmgate.iam.integration.application.federation.OidcUpstreamAdapter oidcUpstreamAdapter(
        io.wyrmgate.iam.integration.application.federation.FederationTrustRepository trusts,JdbcIdpFederationInteraction pending,IdpProtocolProperties properties,
        @org.springframework.beans.factory.annotation.Value("${iam.idp.federation.allowed-origins:}") String origins){
        var allowed=java.util.Arrays.stream(origins.split(",")).map(String::trim).filter(v->!v.isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new io.wyrmgate.iam.integration.application.federation.OidcUpstreamAdapter(trusts,pending,
            new io.wyrmgate.iam.integration.application.federation.HttpOidcTokenExchange(allowed),properties.requiredIssuer()+IdpFederationFlow.CALLBACK);
    }
    @Bean
    IdpFederationFlow idpFederationFlow(IdpLoginInteractionService local,JdbcIdpFederationInteraction pending,
        io.wyrmgate.iam.integration.application.federation.FederationTrustRepository trusts,
        io.wyrmgate.iam.integration.application.federation.OidcUpstreamAdapter adapter,
        io.wyrmgate.iam.idp.federation.FederatedSubjectResolver resolver,io.wyrmgate.iam.identity.application.PrincipalTechnicalReferenceQuery principals,
        IdpBrowserSessionService sessions,IdpProtocolService protocol,IdpSessionTokenCodec tokens,
        @org.springframework.beans.factory.annotation.Value("${iam.idp.federation.enabled:false}") boolean enabled,
        @org.springframework.beans.factory.annotation.Value("${iam.idp.federation.allowed-origins:}") String origins){
        var allowed=java.util.Arrays.stream(origins.split(",")).map(String::trim).filter(v->!v.isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new IdpFederationFlow(enabled,allowed,local,pending,trusts,adapter,resolver,principals,sessions,protocol,tokens);
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
            IdGenerator ids, IdpSecurityEvidence evidence) {
        return new IdpProtocolService(
                clients, sessions, applicationAccess, authorizationCodes, tokens, ids, evidence);
    }
}
