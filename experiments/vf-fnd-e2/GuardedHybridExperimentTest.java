package io.wyrmgate.iam.idp.protocol;

import io.wyrmgate.iam.catalog.application.SsoClientProtocolQuery;
import io.wyrmgate.iam.catalog.domain.*;
import io.wyrmgate.iam.idp.runtime.IdpSecurityEvidence;
import io.wyrmgate.iam.idp.federation.FederatedSubjectResolver;
import io.wyrmgate.iam.credential.application.CredentialAuthenticationService;
import io.wyrmgate.iam.identity.application.IdentityAuthenticationQuery;
import io.wyrmgate.iam.tenancy.application.TenantOverviewQuery;
import io.wyrmgate.iam.tenancy.domain.TenantLifecycleState;
import io.wyrmgate.iam.idp.session.*;
import io.wyrmgate.iam.platform.crypto.*;
import io.wyrmgate.iam.platform.persistence.SharedPostgresIntegrationDatabase;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import io.wyrmgate.iam.tenancy.persistence.TenantTestFixture;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.nimbusds.jose.*;
import com.nimbusds.jose.jca.JCAContext;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.*;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import java.security.interfaces.RSAPublicKey;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.settings.*;
import org.springframework.security.oauth2.server.authorization.token.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GuardedHybridExperimentTest {
    static final String ISS="https://iam.example.test", CLIENT="wc_1234567890abcdef1234567890abcdef";
    static final String REDIRECT="https://client.example.test/callback", VERIFIER="a".repeat(43);
    static final String NONCE="nonce-fixture-1234567890";
    static final Instant NOW=Instant.parse("2026-11-16T00:00:00Z");
    static JdbcTemplate jdbc;
    static SigningKeyProvider keys;
    static RSAPublicKey publicKey;
    static final IdpProtocolProperties PROPERTIES=new IdpProtocolProperties(true,ISS);
    @BeforeAll static void setup() throws Exception {
        jdbc=SharedPostgresIntegrationDatabase.jdbc();
        var gen=KeyPairGenerator.getInstance("RSA"); gen.initialize(2048);
        var pair=gen.generateKeyPair(); publicKey=(RSAPublicKey)pair.getPublic();
        var material=new SigningKeyMaterial("fixture-key","SHA256withRSA",publicKey);
        keys=new SigningKeyProvider(){
            public SigningKeyMaterial currentSigningKey(){return material;}
            public Optional<SigningKeyMaterial> verificationKey(String kid){return material.keyId().equals(kid)?Optional.of(material):Optional.empty();}
            public byte[] sign(byte[] data){
                try {var s=Signature.getInstance(material.signingAlgorithm());s.initSign(pair.getPrivate());s.update(data);return s.sign();}
                catch(GeneralSecurityException e){throw new IllegalStateException(e);}
            }
        };
    }
    // A: unchanged IAM issuer; B: Spring token generation, existing key custody interface.
    static IdpTokenIssuer issuer(boolean hybrid){return hybrid?new HybridIssuer(keys):new IdpJwtIssuer(PROPERTIES,keys,JsonMapper.builder().build());}
    static final class HybridIssuer implements IdpTokenIssuer {
        private final SigningKeyProvider keys;
        private final JwtGenerator generator;
        HybridIssuer(SigningKeyProvider keys){
            this.keys=keys;
            this.generator=new JwtGenerator(this::encode);
            generator.setJwtCustomizer(ctx->{
                // Framework defaults are not IAM's profile. Rebuild only from the trusted, ephemeral projection.
                var projection=(Map<String,Object>)ctx.getAuthorization().getAttribute("fixture-claims");
                boolean id="id_token".equals(ctx.getTokenType().getValue());
                ctx.getClaims().claims(c->{c.clear();c.putAll(projection);});
                if(id){
                    ctx.getClaims().claims(c->{c.remove("client_id");c.remove("scope");});
                    ctx.getClaims().claim("auth_time",ctx.getAuthorization().getAttribute("fixture-auth-time"));
                    String nonce=ctx.getAuthorization().getAttribute("fixture-nonce");
                    if(nonce!=null)ctx.getClaims().claim("nonce",nonce);
                }
                ctx.getJwsHeader().type(id?"JWT":"at+jwt");
            });
        }
        // Supported Nimbus signing SPI; the encoder has only public material + sign(bytes).
        // RS256 fixture only; does not claim production KMS/HSM/rotation support.
        private Jwt encode(JwtEncoderParameters p){
            try{
                var material=keys.currentSigningKey();
                if(!"SHA256withRSA".equals(material.signingAlgorithm()) ||
                    keys.verificationKeys().stream().filter(k->k.keyId().equals(material.keyId())).count()!=1 ||
                    keys.verificationKeys().stream().noneMatch(k->k.keyId().equals(material.keyId()) &&
                        k.signingAlgorithm().equals(material.signingAlgorithm()) &&
                        Arrays.equals(k.publicKey().getEncoded(),material.publicKey().getEncoded()))) throw new IllegalStateException("key not published");
                var header=new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(material.keyId())
                    .type(new JOSEObjectType((String)p.getJwsHeader().getHeaders().get("typ"))).build();
                var claims=new JWTClaimsSet.Builder();
                p.getClaims().getClaims().forEach((k,v)->claims.claim(k,v instanceof Instant i?Date.from(i):v));
                var signed=new SignedJWT(header,claims.build());
                signed.sign(new JWSSigner(){
                    public Set<JWSAlgorithm> supportedJWSAlgorithms(){return Set.of(JWSAlgorithm.RS256);}
                    public JCAContext getJCAContext(){return new JCAContext();}
                    public Base64URL sign(JWSHeader h,byte[] input){
                        if(!JWSAlgorithm.RS256.equals(h.getAlgorithm()))throw new IllegalStateException("algorithm");
                        return Base64URL.encode(keys.sign(input));
                    }
                });
                if(!signed.verify(new RSASSAVerifier((RSAPublicKey)material.publicKey())))throw new IllegalStateException("signature mismatch");
                return new Jwt(signed.serialize(),p.getClaims().getIssuedAt(),p.getClaims().getExpiresAt(),
                    header.toJSONObject(),p.getClaims().getClaims());
            }catch(Exception e){throw new JwtEncodingException("fixture signing failed",e);}
        }
        public TokenPair issue(TenantContext tenant,IdpAuthorizationCode code,Instant now){
            String sub=Base64.getUrlEncoder().withoutPadding().encodeToString(IdpJwtIssuer.sha256(
                ("wyrmgate-sub-v1|"+tenant.tenantId()+"|"+code.identityId()).getBytes(StandardCharsets.UTF_8)));
            // Transient projection from already checked code; no RegisteredClientRepository or saved OAuth2Authorization.
            var client=RegisteredClient.withId(code.clientRegistrationId().toString()).clientId(code.clientId())
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE).authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(code.redirectUri()).scope("openid").scope("profile").build();
            var request=OAuth2AuthorizationRequest.authorizationCode().authorizationUri(ISS+"/oauth2/authorize")
                .clientId(code.clientId()).redirectUri(code.redirectUri()).scopes(Set.of("openid","profile")).build();
            var claims=new LinkedHashMap<String,Object>();
            claims.put("iss",ISS);claims.put("sub",sub);claims.put("aud",List.of(code.clientId()));
            claims.put("iat",now);claims.put("exp",now.plusSeconds(300));claims.put("client_id",code.clientId());
            claims.put("scope",IdpProtocolService.scopeValue(code.scopes()));
            var authBuilder=OAuth2Authorization.withRegisteredClient(client).principalName(sub)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).authorizedScopes(Set.of("openid","profile"))
                .attribute(OAuth2AuthorizationRequest.class.getName(),request)
                .attribute("fixture-claims",Map.copyOf(claims)).attribute("fixture-auth-time",code.authTime());
            if(code.nonce()!=null)authBuilder.attribute("fixture-nonce",code.nonce());
            var auth=authBuilder.build();
            var context=new AuthorizationServerContext(){
                public String getIssuer(){return ISS;}
                public AuthorizationServerSettings getAuthorizationServerSettings(){return AuthorizationServerSettings.builder().issuer(ISS).build();}
            };
            var principal=UsernamePasswordAuthenticationToken.authenticated(sub,"unused",List.of());
            var builder=DefaultOAuth2TokenContext.builder().registeredClient(client).principal(principal)
                .authorizationServerContext(context).authorization(auth).authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(Set.of("openid","profile"));
            var access=generator.generate(builder.tokenType(OAuth2TokenType.ACCESS_TOKEN).build());
            var id=generator.generate(builder.tokenType(new OAuth2TokenType("id_token")).build());
            return new TokenPair(id.getTokenValue(),access.getTokenValue(),300);
        }
    }
    static final class Fixture {
        final TenantContext tenant, other;
        final UUID reg=UUID.randomUUID(), app=UUID.randomUUID(), principal=UUID.randomUUID(), identity=UUID.randomUUID(), credential=UUID.randomUUID();
        UUID session; String browserToken;
        final Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        final AtomicReference<String> authority=new AtomicReference<>("ALLOW");
        final AtomicReference<SsoClientRegistration> registration;
        final AtomicInteger issues=new AtomicInteger();
        final IdpBrowserSessionService sessions;
        final JdbcIdpBrowserSessionRepository sessionRepo=new JdbcIdpBrowserSessionRepository(jdbc);
        final IdpSecurityEvidence evidence=mock(IdpSecurityEvidence.class);
        final IdpProtocolService service;
        final MockMvc mvc;
        Fixture(boolean hybrid){
            var t=new TenantTestFixture(jdbc,UUID::randomUUID);
            tenant=new TenantContext(t.createActive("E2 fixture",now).id());
            other=new TenantContext(t.createActive("E2 other",now).id());
            registration=new AtomicReference<>(client(1));
            var identities=mock(IdentityAuthenticationQuery.class);
            when(identities.eligibleSubject(tenant,principal)).thenAnswer(inv->Optional.of(
                new IdentityAuthenticationQuery.AuthenticationSubject(principal,identity,
                    "PRINCIPAL_REV".equals(authority.get())?2:1,"IDENTITY_REV".equals(authority.get())?2:1)));
            var tenants=mock(TenantOverviewQuery.class);
            when(tenants.find(tenant.tenantId())).thenAnswer(inv->Optional.of(
                new TenantOverviewQuery.TenantSummary(tenant.tenantId(),"fixture",TenantLifecycleState.ACTIVE,
                    "TENANT_REV".equals(authority.get())?2:1,now.minusSeconds(120),now)));
            var credentials=mock(CredentialAuthenticationService.class);
            when(credentials.isStillValid(eq(tenant),eq(principal),eq(credential),eq(1L),any(Instant.class)))
                .thenAnswer(inv->!"CREDENTIAL_REV".equals(authority.get()));
            sessions=new IdpBrowserSessionService(sessionRepo,identities,tenants,credentials,new IdpSessionTokenCodec(),
                UUID::randomUUID,evidence,mock(FederatedSubjectResolver.class));
            newSession();
            var signer=issuer(hybrid);
            service=new IdpProtocolService(id->CLIENT.equals(id)?Optional.of(new SsoClientProtocolQuery.ResolvedClient(tenant,registration.get())):
                "other-client".equals(id)?Optional.of(new SsoClientProtocolQuery.ResolvedClient(other,
                    new SsoClientRegistration(UUID.randomUUID(),app,"other-client",Set.of(REDIRECT),Set.of(SsoClientScope.OPENID),true,
                        SsoClientLifecycleState.ACTIVE,1,now.minusSeconds(120),now.minusSeconds(30)))):Optional.empty(),
                sessions,(tnt,i,a,at)->{
                    if("OUTAGE".equals(authority.get()))throw new IllegalStateException("fixture unavailable");
                    return !"DENY".equals(authority.get());
                },new JdbcIdpAuthorizationCodeRepository(jdbc),(tnt,code,at)->{
                    if("SIGN_FAIL".equals(authority.get()))throw new IllegalStateException("fixture signing unavailable");
                    var result=signer.issue(tnt,code,at);issues.incrementAndGet();return result;
                },UUID::randomUUID,evidence);
            mvc=MockMvcBuilders.standaloneSetup(new IdpAuthorizationController(service,null,sessions,null,null),
                new IdpProtocolController(PROPERTIES,keys)).build();
        }
        SsoClientRegistration client(long rev){
            return new SsoClientRegistration(reg,app,CLIENT,Set.of(REDIRECT),Set.of(SsoClientScope.OPENID,SsoClientScope.PROFILE),
                true,SsoClientLifecycleState.ACTIVE,rev,now.minusSeconds(120),now.minusSeconds(30));
        }
        void newSession(){
            var established=sessions.establish(tenant,new IdentityAuthenticationQuery.AuthenticationSubject(principal,identity,1,1),
                new CredentialAuthenticationService.VerifiedCredential(tenant.tenantId(),principal,credential,1),now.minusSeconds(60));
            session=established.context().sessionId();browserToken=established.token();
        }
        String code(){
            newSession();
            var challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(IdpJwtIssuer.sha256(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
            return service.authorize(new IdpProtocolService.AuthorizationRequest("code",CLIENT,REDIRECT,"openid profile",
                "state-1234567890abcdef",challenge,"S256",NONCE),browserToken,now).code();
        }
        IdpProtocolService.TokenResult redeem(String raw,String verifier,String client,String redirect){
            return service.redeem(new IdpProtocolService.TokenRequest("authorization_code",raw,client,redirect,verifier),now.plusSeconds(1));
        }
        String hash(String raw){return HexFormat.of().formatHex(IdpJwtIssuer.sha256(raw.getBytes(StandardCharsets.US_ASCII)));}
        boolean consumed(String raw){return Boolean.TRUE.equals(jdbc.queryForObject(
            "select consumed_at is not null from platform.idp_authorization_code where tenant_id=? and code_hash=?",Boolean.class,tenant.tenantId(),hash(raw)));}
        void denied(String raw,String verifier,String client,String redirect,String expected){
            assertEquals(expected,assertThrows(IdpProtocolService.ProtocolException.class,()->redeem(raw,verifier,client,redirect)).error());
        }
    }
    static void verifyToken(String raw, String type) throws Exception {
        var jwt=SignedJWT.parse(raw);assertTrue(jwt.verify(new RSASSAVerifier(publicKey)));
        assertEquals(type,jwt.getHeader().getType().toString());
        assertEquals("fixture-key",jwt.getHeader().getKeyID());
    }
    @Test void realRepositoryAndServicePreserveOneUseAndGuardFailures() throws Exception {
        for(boolean hybrid:new boolean[]{false,true}){
            var f=new Fixture(hybrid);
            String raw=f.code();
            var idleBefore=f.sessionRepo.findById(f.tenant,f.session).orElseThrow().idleExpiresAt();
            assertEquals(f.hash(raw),jdbc.queryForObject("select code_hash from platform.idp_authorization_code where tenant_id=? and code_hash=?",
                String.class,f.tenant.tenantId(),f.hash(raw)));
            assertFalse(jdbc.queryForObject("select code_hash=? from platform.idp_authorization_code where tenant_id=? and code_hash=?",
                Boolean.class,raw,f.tenant.tenantId(),f.hash(raw)));
            var gate=new CyclicBarrier(20);int success=0;
            try(var pool=Executors.newFixedThreadPool(20)){
                List<Future<Boolean>> futures=new ArrayList<>();
                for(int i=0;i<20;i++)futures.add(pool.submit(()->{
                    gate.await(20,TimeUnit.SECONDS);
                    try {f.redeem(raw,VERIFIER,CLIENT,REDIRECT);return true;}
                    catch(IdpProtocolService.ProtocolException e){assertEquals("invalid_grant",e.error());return false;}
                }));
                for(var future:futures)if(future.get(30,TimeUnit.SECONDS))success++;
            }
            assertEquals(1,success);assertEquals(1,f.issues.get());assertTrue(f.consumed(raw));
            assertEquals(idleBefore,f.sessionRepo.findById(f.tenant,f.session).orElseThrow().idleExpiresAt());
            for(String reason:List.of("PKCE","REDIRECT","REVISION","SESSION_REVOKED","IDENTITY_REV","PRINCIPAL_REV","TENANT_REV","CREDENTIAL_REV","DENY","OUTAGE","SIGN_FAIL")){
                f.authority.set("ALLOW");f.registration.set(f.client(1));
                String code=f.code();
                if("REVISION".equals(reason))f.registration.set(f.client(2));
                if("SESSION_REVOKED".equals(reason))f.sessions.logout(f.tenant,f.browserToken,f.now.plusSeconds(1));
                if(Set.of("IDENTITY_REV","PRINCIPAL_REV","TENANT_REV","CREDENTIAL_REV","DENY","OUTAGE","SIGN_FAIL").contains(reason))f.authority.set(reason);
                String err=Set.of("OUTAGE","SIGN_FAIL").contains(reason)?"temporarily_unavailable":"invalid_grant";
                f.denied(code,"PKCE".equals(reason)?"b".repeat(43):VERIFIER,CLIENT,
                    "REDIRECT".equals(reason)?"https://wrong.example.test/callback":REDIRECT,err);
                assertTrue(f.consumed(code));
                f.authority.set("ALLOW");f.registration.set(f.client(1));
                f.denied(code,VERIFIER,CLIENT,REDIRECT,"invalid_grant");
            }
            String cross=f.code();f.denied(cross,VERIFIER,"other-client",REDIRECT,"invalid_grant");
            assertFalse(f.consumed(cross)); // tenant isolation: cannot burn foreign code
            f.redeem(cross,VERIFIER,CLIENT,REDIRECT);assertEquals(2,f.issues.get());
            System.out.println("E2 "+(hybrid?"B":"A")+": 20-way redemption successes="+success+"; eleven failure paths burn; idle unchanged; foreign tenant cannot consume");
        }
    }
    @Test void actualMvcEndpointsAndLegacyClaimProfile() throws Exception {
        for(boolean hybrid:new boolean[]{false,true}){
            var f=new Fixture(hybrid);
            f.mvc.perform(get("/.well-known/openid-configuration")).andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value(ISS)).andExpect(jsonPath("$.grant_types_supported[0]").value("authorization_code"));
            f.mvc.perform(get("/oauth2/jwks")).andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kid").value("fixture-key")).andExpect(jsonPath("$.keys[0].d").doesNotExist());
            var challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(IdpJwtIssuer.sha256(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
            var redirect=f.mvc.perform(get("/oauth2/authorize")
                .cookie(new jakarta.servlet.http.Cookie(IdpAuthorizationController.BROWSER_SESSION_COOKIE,f.browserToken))
                .param("response_type","code").param("client_id",CLIENT).param("redirect_uri",REDIRECT)
                .param("scope","openid profile").param("state","state-1234567890abcdef").param("nonce",NONCE)
                .param("code_challenge",challenge).param("code_challenge_method","S256"))
                .andExpect(status().isFound()).andReturn().getResponse().getHeader("Location");
            assertNotNull(redirect);assertTrue(redirect.startsWith(REDIRECT+"?"));
            var query=org.springframework.web.util.UriComponentsBuilder.fromUriString(redirect).build().getQueryParams();
            assertEquals("state-1234567890abcdef",query.getFirst("state"));
            String code=query.getFirst("code");assertNotNull(code);
            var response=f.mvc.perform(post("/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type","authorization_code").param("code",code).param("client_id",CLIENT)
                .param("redirect_uri",REDIRECT).param("code_verifier",VERIFIER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token_type").value("Bearer")).andReturn().getResponse();
            var data=JsonMapper.builder().build().readTree(response.getContentAsString());
            String access=data.path("access_token").asText(), id=data.path("id_token").asText();
            verifyToken(access,"at+jwt");verifyToken(id,"JWT");
            var a=SignedJWT.parse(access).getJWTClaimsSet();var i=SignedJWT.parse(id).getJWTClaimsSet();
            assertEquals(Set.of("iss","sub","aud","iat","exp","client_id","scope"),a.getClaims().keySet());
            assertEquals(Set.of("iss","sub","aud","iat","exp","auth_time","nonce"),i.getClaims().keySet());
            assertEquals(List.of(CLIENT),a.getAudience());assertEquals(List.of(CLIENT),i.getAudience());
            assertEquals(CLIENT,a.getStringClaim("client_id"));assertEquals("openid profile",a.getStringClaim("scope"));
            assertEquals(a.getSubject(),i.getSubject());assertEquals(NONCE,i.getStringClaim("nonce"));
            String expectedSubject=Base64.getUrlEncoder().withoutPadding().encodeToString(IdpJwtIssuer.sha256(
                ("wyrmgate-sub-v1|"+f.tenant.tenantId()+"|"+f.identity).getBytes(StandardCharsets.UTF_8)));
            assertEquals(expectedSubject,a.getSubject());
            assertEquals(f.now.minusSeconds(60).getEpochSecond(),i.getLongClaim("auth_time"));
            assertNull(a.getClaim("auth_time"));
            assertEquals(300,(i.getExpirationTime().getTime()-i.getIssueTime().getTime())/1000);
            assertEquals(300,(a.getExpirationTime().getTime()-a.getIssueTime().getTime())/1000);
            f.mvc.perform(post("/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type","authorization_code").param("code",code).param("client_id",CLIENT)
                .param("redirect_uri",REDIRECT).param("code_verifier",VERIFIER))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));
        }
    }
    @Test void boundedSameFixtureRedemptionLatency() throws Exception {
        for(boolean hybrid:new boolean[]{false,true}){
            var f=new Fixture(hybrid);
            for(int i=0;i<50;i++)f.redeem(f.code(),VERIFIER,CLIENT,REDIRECT);
            List<String> codes=new ArrayList<>();for(int i=0;i<1000;i++)codes.add(f.code());
            List<Long> times=new ArrayList<>();
            try(var pool=Executors.newFixedThreadPool(10)){
                List<Future<Long>> futures=new ArrayList<>();
                for(String code:codes)futures.add(pool.submit(()->{
                    long start=System.nanoTime();f.redeem(code,VERIFIER,CLIENT,REDIRECT);return System.nanoTime()-start;
                }));
                for(var future:futures)times.add(future.get(60,TimeUnit.SECONDS));
            }
            Collections.sort(times);assertEquals(1050,f.issues.get());
            System.out.printf(Locale.ROOT,"E2 LATENCY %s n=1000 concurrency=10 warmup=50 p50=%.3fms p95=%.3fms p99=%.3fms errors=0%n",
                hybrid?"B":"A",times.get(499)/1e6,times.get(949)/1e6,times.get(989)/1e6);
        }
    }
}
