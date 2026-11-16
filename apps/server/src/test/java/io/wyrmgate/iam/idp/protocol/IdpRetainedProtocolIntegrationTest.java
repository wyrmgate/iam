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
import com.nimbusds.jwt.*;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import java.security.interfaces.RSAPublicKey;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class IdpRetainedProtocolIntegrationTest {
    static final String ISS="https://iam.example.test", CLIENT="wc_1234567890abcdef1234567890abcdef";
    static final String REDIRECT="https://client.example.test/callback", VERIFIER="a".repeat(43);
    static final String NONCE="nonce-fixture-1234567890";
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
        final AtomicInteger committedResponseLosses=new AtomicInteger();
        final java.util.concurrent.atomic.AtomicBoolean loseConsumeResponse=new java.util.concurrent.atomic.AtomicBoolean();
        final MockMvc mvc;
        Fixture(){
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
            var signer=new IdpJwtIssuer(PROPERTIES,keys,JsonMapper.builder().build());
            service=new IdpProtocolService(id->CLIENT.equals(id)?Optional.of(new SsoClientProtocolQuery.ResolvedClient(tenant,registration.get())):
                "other-client".equals(id)?Optional.of(new SsoClientProtocolQuery.ResolvedClient(other,
                    new SsoClientRegistration(UUID.randomUUID(),app,"other-client",Set.of(REDIRECT),Set.of(SsoClientScope.OPENID),true,
                        SsoClientLifecycleState.ACTIVE,1,now.minusSeconds(120),now.minusSeconds(30)))):Optional.empty(),
                sessions,(tnt,i,a,at)->{
                    if("OUTAGE".equals(authority.get()))throw new IllegalStateException("fixture unavailable");
                    return !"DENY".equals(authority.get());
                },new IdpAuthorizationCodeRepository(){
                    private final JdbcIdpAuthorizationCodeRepository delegate=new JdbcIdpAuthorizationCodeRepository(jdbc);
                    public void insert(TenantContext tenant,IdpAuthorizationCode code){delegate.insert(tenant,code);}
                    public Optional<IdpAuthorizationCode> consume(TenantContext tenant,String hash,Instant at){
                        var result=delegate.consume(tenant,hash,at);
                        if(result.isPresent()&&loseConsumeResponse.compareAndSet(true,false)){
                            // The actual autocommit UPDATE RETURNING has finished. Observe on a separate
                            // connection before withholding its result from the protocol service.
                            var committed=jdbc.queryForObject("select consumed_at is not null from platform.idp_authorization_code where tenant_id=? and code_hash=?",
                                Boolean.class,tenant.tenantId(),hash);
                            assertEquals(Boolean.TRUE,committed);
                            committedResponseLosses.incrementAndGet();
                            throw new org.springframework.dao.DataAccessResourceFailureException("fixture committed result lost");
                        }
                        return result;
                    }
                },(tnt,code,at)->{
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
        {
            var f=new Fixture();
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
            System.out.println("Retained IdP: 20-way redemption successes="+success+"; eleven failure paths burn; idle unchanged; foreign tenant cannot consume");
        }
    }
    @Test void committedConsumeResultLossNeverSignsOrRestoresAndRetryDenies() {
        var f=new Fixture();
        String raw=f.code();
        var before=f.sessionRepo.findById(f.tenant,f.session).orElseThrow().idleExpiresAt();
        f.loseConsumeResponse.set(true);
        f.denied(raw,VERIFIER,CLIENT,REDIRECT,"temporarily_unavailable");
        assertEquals(1,f.committedResponseLosses.get());
        assertTrue(f.consumed(raw));
        assertEquals(0,f.issues.get());
        f.denied(raw,VERIFIER,CLIENT,REDIRECT,"invalid_grant");
        assertTrue(f.consumed(raw));
        assertEquals(0,f.issues.get());
        assertEquals(before,f.sessionRepo.findById(f.tenant,f.session).orElseThrow().idleExpiresAt());
        // A fresh authorization flow can still succeed; only the original code is burned.
        f.redeem(f.code(),VERIFIER,CLIENT,REDIRECT);
        assertEquals(1,f.issues.get());
        System.out.println("G3 acceptance: actual committed JDBC consume result lost at repository boundary; durable consumed, zero issue, retry denied, fresh flow succeeds");
    }

    @Test void actualMvcEndpointsAndLegacyClaimProfile() throws Exception {
        {
            var f=new Fixture();
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
}
