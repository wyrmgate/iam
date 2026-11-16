package io.wyrmgate.iam.idp.protocol;

import io.wyrmgate.iam.platform.persistence.SharedPostgresIntegrationDatabase;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
import com.nimbusds.jose.*;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.authentication.*;
import org.springframework.security.oauth2.server.authorization.client.*;
import org.springframework.security.oauth2.server.authorization.context.*;
import org.springframework.security.oauth2.server.authorization.settings.*;
import static org.junit.jupiter.api.Assertions.*;

class AssertionReplayExperimentTest {
    static final String ISS="https://iam.example.test", ENDPOINT=ISS+"/oauth2/token", CLIENT="synthetic-service";
    static JdbcTemplate jdbc;
    static RSAKey key,other;
    @BeforeAll static void setup() throws Exception{
        jdbc=SharedPostgresIntegrationDatabase.jdbc();
        jdbc.execute("create schema experiment");
        jdbc.execute("""
            create table experiment.assertion_replay (
              issuer varchar(256) not null, client varchar(128) not null, jti_hash char(64) not null,
              expires_at timestamptz not null, primary key(issuer,client,jti_hash)
            )
            """);
        key=new RSAKeyGenerator(2048).keyID("fixture").generate();
        other=new RSAKeyGenerator(2048).keyID("other").generate();
    }
    static void context(){
        AuthorizationServerContextHolder.setContext(new AuthorizationServerContext(){
            public String getIssuer(){return ISS;}
            public AuthorizationServerSettings getAuthorizationServerSettings(){return AuthorizationServerSettings.builder().issuer(ISS).build();}
        });
    }
    @BeforeEach void start(){context();}
    @AfterEach void end(){AuthorizationServerContextHolder.resetContext();}
    static String signed(RSAKey signer,String client,String aud,String sub,String jti,Instant iat,Instant exp) throws Exception{
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signer.getKeyID()).build(),
            new JWTClaimsSet.Builder().issuer(client).subject(sub).audience(aud).jwtID(jti)
                .issueTime(Date.from(iat)).expirationTime(Date.from(exp)).build());
        jwt.sign(new RSASSASigner(signer));return jwt.serialize();
    }
    static OAuth2ClientAuthenticationToken req(String client,String assertion){
        return new OAuth2ClientAuthenticationToken(client,new ClientAuthenticationMethod("urn:ietf:params:oauth:client-assertion-type:jwt-bearer"),
            assertion,Map.of("grant_type","client_credentials"));
    }
    static JwtClientAssertionAuthenticationProvider provider(String client) throws Exception{
        var registration=RegisteredClient.withId("fixture-"+client).clientId(client)
            .clientAuthenticationMethod(ClientAuthenticationMethod.PRIVATE_KEY_JWT)
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS).scope("files.write")
            .clientSettings(ClientSettings.builder().tokenEndpointAuthenticationSigningAlgorithm(SignatureAlgorithm.RS256).build()).build();
        var p=new JwtClientAssertionAuthenticationProvider(new InMemoryRegisteredClientRepository(registration),new InMemoryOAuth2AuthorizationService());
        var decoder=NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
        decoder.setJwtValidator(JwtClientAssertionDecoderFactory.DEFAULT_JWT_VALIDATOR_FACTORY.apply(registration));
        p.setJwtDecoderFactory(ignored->raw->{
            var jwt=decoder.decode(raw); // signature + standard issuer/sub/audience/expiry first
            Instant now=Instant.now();
            if(!List.of(ENDPOINT).equals(jwt.getAudience()) || jwt.getId()==null || jwt.getId().isBlank() || jwt.getId().length()>128 ||
                jwt.getIssuedAt()==null || jwt.getExpiresAt()==null || jwt.getIssuedAt().isAfter(now.plusSeconds(30)) ||
                !jwt.getExpiresAt().isAfter(now) || !jwt.getExpiresAt().isAfter(jwt.getIssuedAt()) ||
                jwt.getExpiresAt().isAfter(jwt.getIssuedAt().plusSeconds(120))) throw new JwtException("fixture policy denied");
            String hash=HexFormat.of().formatHex(IdpJwtIssuer.sha256(jwt.getId().getBytes(StandardCharsets.UTF_8)));
            try{
                int written=jdbc.update("""
                    insert into experiment.assertion_replay(issuer,client,jti_hash,expires_at)
                    values (?,?,?,?) on conflict do nothing
                    """,ISS,client,hash,java.sql.Timestamp.from(jwt.getExpiresAt()));
                if(written!=1)throw new JwtException("fixture replay denied");
            }catch(org.springframework.dao.DataAccessException unavailable){throw new JwtException("fixture replay unavailable");}
            return jwt;
        });
        return p;
    }
    @Test void durableReplayAcrossProvidersAndRestartAndScope() throws Exception{
        var a=provider(CLIENT);var b=provider(CLIENT);
        Instant now=Instant.now();String token=signed(key,CLIENT,ENDPOINT,CLIENT,"same-jti",now,now.plusSeconds(60));
        var gate=new CyclicBarrier(20);int success=0;
        try(var pool=Executors.newFixedThreadPool(20)){
            List<Future<Boolean>> futures=new ArrayList<>();
            for(int i=0;i<20;i++){
                final var p=i%2==0?a:b;
                futures.add(pool.submit(()->{
                    context();
                    try{gate.await(20,TimeUnit.SECONDS);return p.authenticate(req(CLIENT,token)).isAuthenticated();}
                    catch(OAuth2AuthenticationException e){assertEquals("invalid_client",e.getError().getErrorCode());return false;}
                    finally{AuthorizationServerContextHolder.resetContext();}
                }));
            }
            for(var f:futures)if(f.get(30,TimeUnit.SECONDS))success++;
        }
        assertEquals(1,success);
        assertThrows(OAuth2AuthenticationException.class,()->provider(CLIENT).authenticate(req(CLIENT,token)));
        String client2="synthetic-service-2";
        assertTrue(provider(client2).authenticate(req(client2,signed(key,client2,ENDPOINT,client2,"same-jti",now,now.plusSeconds(60)))).isAuthenticated());
        String digest=HexFormat.of().formatHex(IdpJwtIssuer.sha256("same-jti".getBytes(StandardCharsets.UTF_8)));
        assertEquals(2,jdbc.queryForObject("select count(*) from experiment.assertion_replay where jti_hash=?",Integer.class,digest));
        System.out.println("E3 durable assertion replay: 1 of 20 across 2 providers; recreated provider denied; separate client isolated");
    }
    @Test void invalidAssertionsDoNotReserveReplayAndExpiryCleanupIsBounded() throws Exception{
        var p=provider(CLIENT);Instant now=Instant.now();
        for(String raw:List.of(
            signed(other,CLIENT,ENDPOINT,CLIENT,"invalid",now,now.plusSeconds(60)),
            signed(key,CLIENT,ISS,CLIENT,"invalid",now,now.plusSeconds(60)),
            signed(key,CLIENT,ENDPOINT,"other-sub","invalid",now,now.plusSeconds(60)),
            signed(key,CLIENT,ENDPOINT,CLIENT,"invalid",now.minusSeconds(300),now.minusSeconds(100)),
            signed(key,CLIENT,ENDPOINT,CLIENT,"invalid",now,now.plusSeconds(300)))){
            assertThrows(OAuth2AuthenticationException.class,()->p.authenticate(req(CLIENT,raw)));
        }
        assertTrue(p.authenticate(req(CLIENT,signed(key,CLIENT,ENDPOINT,CLIENT,"invalid",now,now.plusSeconds(60)))).isAuthenticated());
        jdbc.update("insert into experiment.assertion_replay values (?,?,?,?)",ISS,CLIENT,"a".repeat(64),java.sql.Timestamp.from(now.minusSeconds(120)));
        int removed=jdbc.update("delete from experiment.assertion_replay where (issuer,client,jti_hash) in (select issuer,client,jti_hash from experiment.assertion_replay where expires_at < ? order by expires_at limit 1)",java.sql.Timestamp.from(now.minusSeconds(30)));
        assertEquals(1,removed);
        assertEquals(0,jdbc.queryForObject("select count(*) from experiment.assertion_replay where jti_hash=?",Integer.class,"a".repeat(64)));
        // Simulated database outage: no fallback to accepting assertion.
        jdbc.execute("alter table experiment.assertion_replay rename to assertion_replay_unavailable");
        try{assertThrows(OAuth2AuthenticationException.class,()->p.authenticate(req(CLIENT,
            signed(key,CLIENT,ENDPOINT,CLIENT,"outage",now,now.plusSeconds(60)))));}
        finally{jdbc.execute("alter table experiment.assertion_replay_unavailable rename to assertion_replay");}
    }
}
