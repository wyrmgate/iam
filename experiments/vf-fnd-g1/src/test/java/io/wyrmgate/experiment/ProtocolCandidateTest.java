package io.wyrmgate.experiment;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.security.oauth2.server.authorization.context.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.authentication.*;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.*;
import org.springframework.security.oauth2.server.authorization.token.OAuth2AccessTokenGenerator;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolCandidateTest {
    static final String RESOURCE="https://storage.example.invalid/api";
    static final String CLIENT="asset-backend-t1";
    @BeforeEach void context() {
        AuthorizationServerContextHolder.setContext(new AuthorizationServerContext() {
            public String getIssuer() { return "https://iam.example.invalid"; }
            public AuthorizationServerSettings getAuthorizationServerSettings() {
                return AuthorizationServerSettings.builder().issuer(getIssuer()).build();
            }
        });
    }
    @AfterEach void clear() { AuthorizationServerContextHolder.resetContext(); }
    // Provider-only comparison. Authentication token is synthetic already-authenticated client;
    // this does not prove private_key_jwt, HTTP endpoints, or a production registration adapter.
    @Test void clientCredentialsProviderPreservesDefaultScopeAndAddsGovernedGate() {
        var registered=RegisteredClient.withId("fixture")
            .clientId(CLIENT).clientAuthenticationMethod(ClientAuthenticationMethod.PRIVATE_KEY_JWT)
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
            .scope("files.write")
            .tokenSettings(TokenSettings.builder().accessTokenFormat(OAuth2TokenFormat.REFERENCE)
                .accessTokenTimeToLive(Duration.ofMinutes(5)).build()).build();
        var principal=new OAuth2ClientAuthenticationToken(registered,ClientAuthenticationMethod.PRIVATE_KEY_JWT,null);
        var active=new AtomicBoolean(true);
        var available=new AtomicBoolean(true);
        var provider=new OAuth2ClientCredentialsAuthenticationProvider(
            new InMemoryOAuth2AuthorizationService(),new OAuth2AccessTokenGenerator());
        var standard=new OAuth2ClientCredentialsAuthenticationValidator();
        provider.setAuthenticationValidator(ctx->{
            standard.accept(ctx); // never replace standard scope check
            var grant=(OAuth2ClientCredentialsAuthenticationToken)ctx.getAuthentication();
            if(!available.get()) throw new OAuth2AuthenticationException("temporarily_unavailable");
            if(!active.get() || !RESOURCE.equals(grant.getAdditionalParameters().get("resource")))
                throw new OAuth2AuthenticationException("invalid_grant");
        });
        var result=(OAuth2AccessTokenAuthenticationToken)provider.authenticate(
            new OAuth2ClientCredentialsAuthenticationToken(principal,Set.of("files.write"),Map.of("resource",RESOURCE)));
        assertEquals(Set.of("files.write"),result.getAccessToken().getScopes());
        assertNull(result.getRefreshToken());
        assertThrows(OAuth2AuthenticationException.class,()->provider.authenticate(
            new OAuth2ClientCredentialsAuthenticationToken(principal,Set.of("files.admin"),Map.of("resource",RESOURCE))));
        assertThrows(OAuth2AuthenticationException.class,()->provider.authenticate(
            new OAuth2ClientCredentialsAuthenticationToken(principal,Set.of("files.write"),Map.of("resource","other"))));
        active.set(false);
        assertThrows(OAuth2AuthenticationException.class,()->provider.authenticate(
            new OAuth2ClientCredentialsAuthenticationToken(principal,Set.of("files.write"),Map.of("resource",RESOURCE))));
        active.set(true);available.set(false);
        assertThrows(OAuth2AuthenticationException.class,()->provider.authenticate(
            new OAuth2ClientCredentialsAuthenticationToken(principal,Set.of("files.write"),Map.of("resource",RESOURCE))));
    }
}
