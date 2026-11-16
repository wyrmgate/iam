package io.wyrmgate.experiment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.authentication.*;
import org.springframework.security.oauth2.server.authorization.client.*;
import org.springframework.security.oauth2.server.authorization.context.*;
import org.springframework.security.oauth2.server.authorization.settings.*;
import org.springframework.security.oauth2.server.authorization.token.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolBoundaryTest {
    static final String ISS="https://iam.example.invalid", TOKEN=ISS+"/oauth2/token";
    static final String REDIRECT="https://client.example.invalid/callback", CLIENT="fixture-browser";
    static final String VERIFIER="a".repeat(43);
    static void context() {
        AuthorizationServerContextHolder.setContext(new AuthorizationServerContext(){
            public String getIssuer(){return ISS;}
            public AuthorizationServerSettings getAuthorizationServerSettings(){
                return AuthorizationServerSettings.builder().issuer(ISS).build();
            }
        });
    }
    @BeforeEach void start(){context();}
    @AfterEach void end(){AuthorizationServerContextHolder.resetContext();}

    record CodeFixture(RegisteredClient client,InMemoryOAuth2AuthorizationService store,String raw) {}
    static CodeFixture code() throws Exception {
        var client=RegisteredClient.withId("fixture-code").clientId(CLIENT)
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri(REDIRECT).scope("files.read")
            .clientSettings(ClientSettings.builder().requireProofKey(true).build())
            .tokenSettings(TokenSettings.builder().accessTokenFormat(OAuth2TokenFormat.REFERENCE).build()).build();
        var challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
        var request=OAuth2AuthorizationRequest.authorizationCode()
            .authorizationUri(ISS+"/oauth2/authorize").clientId(CLIENT).redirectUri(REDIRECT)
            .scopes(Set.of("files.read")).state("fixture-state")
            .additionalParameters(Map.of("code_challenge",challenge,"code_challenge_method","S256")).build();
        var now=Instant.now();
        var raw=UUID.randomUUID().toString();
        var code=new OAuth2AuthorizationCode(raw,now,now.plusSeconds(300));
        var auth=OAuth2Authorization.withRegisteredClient(client).principalName("fixture-human")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizedScopes(Set.of("files.read"))
            .attribute(Principal.class.getName(),UsernamePasswordAuthenticationToken.authenticated("fixture-human","unused",List.of()))
            .attribute(OAuth2AuthorizationRequest.class.getName(),request).token(code).build();
        var store=new InMemoryOAuth2AuthorizationService();
        store.save(auth);
        return new CodeFixture(client,store,raw);
    }
    static OAuth2ClientAuthenticationToken authenticatePublic(CodeFixture f,String verifier) {
        var auth=new PublicClientAuthenticationProvider(new InMemoryRegisteredClientRepository(f.client()),f.store());
        return (OAuth2ClientAuthenticationToken)auth.authenticate(new OAuth2ClientAuthenticationToken(
            CLIENT,ClientAuthenticationMethod.NONE,null,
            Map.of("grant_type","authorization_code","code",f.raw(),"code_verifier",verifier)));
    }
    static OAuth2AuthorizationCodeAuthenticationToken redeem(CodeFixture f,OAuth2ClientAuthenticationToken client,String redirect) {
        return new OAuth2AuthorizationCodeAuthenticationToken(f.raw(),client,redirect,Map.of("code_verifier",VERIFIER));
    }
    @Test void candidateDefaultPkceFailureDoesNotBurnCodeAndSerialReplayIsDenied() throws Exception {
        var f=code();
        assertThrows(OAuth2AuthenticationException.class,()->authenticatePublic(f,"b".repeat(43)));
        // Observed default differs from Wyrmgate ADR-0066/current control: failed proof burns code there.
        var client=authenticatePublic(f,VERIFIER);
        var provider=new OAuth2AuthorizationCodeAuthenticationProvider(f.store(),new OAuth2AccessTokenGenerator());
        assertThrows(OAuth2AuthenticationException.class,()->provider.authenticate(redeem(f,client,"https://wrong.example.invalid")));
        assertNotNull(provider.authenticate(redeem(f,client,REDIRECT)));
        assertThrows(OAuth2AuthenticationException.class,()->provider.authenticate(redeem(f,client,REDIRECT)));
        System.out.println("E2 default: wrong PKCE rejected, same code remains usable; serial replay denied");
    }
    @Test void candidateDefaultInMemoryCodeStoreDoesNotGuaranteeAtomicConcurrentConsumption() throws Exception {
        var f=code();
        var client=authenticatePublic(f,VERIFIER);
        var barrier=new CyclicBarrier(2);
        var delegate=new OAuth2AccessTokenGenerator();
        OAuth2TokenGenerator<OAuth2AccessToken> generator=ctx->{
            try{barrier.await(10,TimeUnit.SECONDS);}catch(Exception e){throw new IllegalStateException("fixture barrier",e);}
            return delegate.generate(ctx);
        };
        var provider=new OAuth2AuthorizationCodeAuthenticationProvider(f.store(),generator);
        try(var pool=Executors.newFixedThreadPool(2)){
            Callable<Boolean> call=()->{
                context();
                try{return provider.authenticate(redeem(f,client,REDIRECT))!=null;}
                finally{AuthorizationServerContextHolder.resetContext();}
            };
            var results=pool.invokeAll(List.of(call,call),20,TimeUnit.SECONDS);
            int successes=0;
            for(var result:results)if(result.get())successes++;
            // Diagnostic assertion: show default in-memory protocol read/generate/save is not our atomic claim.
            assertEquals(2,successes);
            System.out.println("E2 default in-memory deterministic race: successful redemptions="+successes+" of 2 (not acceptable for IAM)");
        }
    }

    static String assertion(RSAKey key,String audience,String subject,String id,Instant expires) throws Exception {
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
            new JWTClaimsSet.Builder().issuer("fixture-service").subject(subject).audience(audience)
                .jwtID(id).issueTime(Date.from(Instant.now())).expirationTime(Date.from(expires)).build());
        jwt.sign(new RSASSASigner(key));return jwt.serialize();
    }
    static OAuth2ClientAuthenticationToken assertionRequest(String token) {
        return new OAuth2ClientAuthenticationToken("fixture-service",
            new ClientAuthenticationMethod("urn:ietf:params:oauth:client-assertion-type:jwt-bearer"),token,Map.of("grant_type","client_credentials"));
    }
    @Test void actualClientAssertionProviderWithPinnedKeyNeedsExplicitReplayAndAudiencePolicy() throws Exception {
        RSAKey key=new RSAKeyGenerator(2048).keyID("fixture").generate();
        RSAKey other=new RSAKeyGenerator(2048).keyID("other").generate();
        var client=RegisteredClient.withId("fixture-service-reg").clientId("fixture-service")
            .clientAuthenticationMethod(ClientAuthenticationMethod.PRIVATE_KEY_JWT)
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS).scope("files.write")
            .clientSettings(ClientSettings.builder().tokenEndpointAuthenticationSigningAlgorithm(SignatureAlgorithm.RS256).build()).build();
        var provider=new JwtClientAssertionAuthenticationProvider(new InMemoryRegisteredClientRepository(client),new InMemoryOAuth2AuthorizationService());
        var decoder=NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
        var standard=JwtClientAssertionDecoderFactory.DEFAULT_JWT_VALIDATOR_FACTORY.apply(client);
        decoder.setJwtValidator(standard);
        provider.setJwtDecoderFactory(ignored->decoder);
        var expires=Instant.now().plusSeconds(60);
        String first=assertion(key,TOKEN,"fixture-service","repeat",expires);
        assertTrue(provider.authenticate(assertionRequest(first)).isAuthenticated());
        assertTrue(provider.authenticate(assertionRequest(first)).isAuthenticated());
        // Default validator accepts issuer audience, not solely exact token endpoint.
        assertTrue(provider.authenticate(assertionRequest(assertion(key,ISS,"fixture-service","issuer-only",expires))).isAuthenticated());
        System.out.println("E3 default assertion validator: same assertion accepted twice; issuer-only audience accepted");
        var seen=ConcurrentHashMap.<String>newKeySet();
        // Fixture-only replay cache, not durable/multi-instance. Decode + standard validation before consuming.
        decoder.setJwtValidator(standard);
        provider.setJwtDecoderFactory(ignored->raw->{
            Jwt jwt=decoder.decode(raw);
            if(!List.of(TOKEN).equals(jwt.getAudience()) || jwt.getId()==null || jwt.getIssuedAt()==null
                || jwt.getExpiresAt()==null || jwt.getExpiresAt().isAfter(jwt.getIssuedAt().plusSeconds(120))
                || !seen.add(jwt.getId())) throw new JwtException("fixture policy denied");
            return jwt;
        });
        String fresh=assertion(key,TOKEN,"fixture-service","fresh",expires);
        assertTrue(provider.authenticate(assertionRequest(fresh)).isAuthenticated());
        assertThrows(OAuth2AuthenticationException.class,()->provider.authenticate(assertionRequest(fresh)));
        assertThrows(OAuth2AuthenticationException.class,()->provider.authenticate(assertionRequest(assertion(key,ISS,"fixture-service","wrong-aud",expires))));
        assertThrows(OAuth2AuthenticationException.class,()->provider.authenticate(assertionRequest(assertion(key,TOKEN,"another-service","wrong-sub",expires))));
        assertThrows(OAuth2AuthenticationException.class,()->provider.authenticate(assertionRequest(assertion(other,TOKEN,"fixture-service","wrong-key",expires))));
        assertThrows(OAuth2AuthenticationException.class,()->provider.authenticate(assertionRequest(assertion(key,TOKEN,"fixture-service","expired",Instant.now().minusSeconds(120)))));
    }
}
