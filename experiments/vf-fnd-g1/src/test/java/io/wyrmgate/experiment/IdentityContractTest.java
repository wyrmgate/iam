package io.wyrmgate.experiment;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

// Test-only acceptance oracle, not a production verifier or integration.
class IdentityContractTest {
    static final String ISS="https://iam.example.invalid", OTHER="https://other.example.invalid";
    static final String AUD="https://storage.example.invalid/api", HUMAN="browser-t1", SERVICE="asset-t1";
    static final Instant NOW=Instant.parse("2026-11-16T00:00:00Z");
    static RSAKey key, rotated;
    @BeforeAll static void keys() throws Exception {
        key=new RSAKeyGenerator(2048).keyID("current").generate();
        rotated=new RSAKeyGenerator(2048).keyID("rotated").generate();
    }
    record Binding(String tenant,String actor,String kind,String client,String subject) {}
    static Map<String,Binding> bindings=Map.of(HUMAN,new Binding("T1","actor-human","USER",HUMAN,"human"),
        SERVICE,new Binding("T1","actor-service","SERVICE",SERVICE,"service"));
    static JWTClaimsSet.Builder claims(String client,String sub) {
        return new JWTClaimsSet.Builder().issuer(ISS).subject(sub).audience(AUD)
            .claim("client_id",client).claim("scope","files.read").jwtID("fixture-id")
            .issueTime(Date.from(NOW.minusSeconds(1))).expirationTime(Date.from(NOW.plusSeconds(299)));
    }
    static String sign(RSAKey k, String type, JWTClaimsSet.Builder c) throws Exception {
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(k.getKeyID()).type(new JOSEObjectType(type)).build(),c.build());
        jwt.sign(new RSASSASigner(k));return jwt.serialize();
    }
    static Binding authorize(String token,String tenant,boolean currentAuthority,boolean localGrant,Map<String,RSAKey> keys,Instant now) throws Exception {
        var jwt=SignedJWT.parse(token);
        var k=keys.get(jwt.getHeader().getKeyID());
        if(k==null || !JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())
           || !new JOSEObjectType("at+jwt").equals(jwt.getHeader().getType())
           || !jwt.verify(new RSASSAVerifier(k.toRSAPublicKey()))) return null;
        var c=jwt.getJWTClaimsSet();
        if(!ISS.equals(c.getIssuer()) || !List.of(AUD).equals(c.getAudience())
            || c.getIssueTime()==null || c.getExpirationTime()==null || c.getJWTID()==null
            || c.getIssueTime().toInstant().isAfter(now.plusSeconds(30))
            || !c.getExpirationTime().toInstant().plusSeconds(30).isAfter(now)
            || !c.getExpirationTime().after(c.getIssueTime())
            || c.getExpirationTime().toInstant().isAfter(c.getIssueTime().toInstant().plusSeconds(300))
            || c.getClaim("act")!=null || c.getClaim("cnf")!=null) return null;
        var b=bindings.get(c.getStringClaim("client_id"));
        return b!=null && b.tenant().equals(tenant) && b.subject().equals(c.getSubject())
            && currentAuthority && localGrant && "files.read".equals(c.getStringClaim("scope")) ? b : null;
    }
    @Test void humanAndServiceAreDistinctAndTenantBound() throws Exception {
        var keys=Map.of("current",key);
        var h=sign(key,"at+jwt",claims(HUMAN,"human")); var s=sign(key,"at+jwt",claims(SERVICE,"service"));
        assertEquals("USER",authorize(h,"T1",true,true,keys,NOW).kind());
        assertEquals("SERVICE",authorize(s,"T1",true,true,keys,NOW).kind());
        assertNull(authorize(h,"T2",true,true,keys,NOW));
        assertNull(authorize(h,"T1",false,true,keys,NOW));
        assertNull(authorize(h,"T1",true,false,keys,NOW));
        assertNull(authorize(sign(key,"at+jwt",claims(SERVICE,"human")),"T1",true,true,keys,NOW));
    }
    @Test void tokenConfusionAndRevocationAndRotation() throws Exception {
        var keys=Map.of("current",key);
        for(var c:List.of(claims(HUMAN,"human").issuer(OTHER),claims(HUMAN,"human").audience(HUMAN),
          claims(HUMAN,"human").audience(List.of(AUD,"other")),claims(HUMAN,"human").jwtID(null),
          claims(HUMAN,"human").claim("act",Map.of("sub","impersonation")),
          claims(HUMAN,"human").expirationTime(Date.from(NOW.plusSeconds(400))))) {
            assertNull(authorize(sign(key,"at+jwt",c),"T1",true,true,keys,NOW));
        }
        assertNull(authorize(sign(key,"JWT",claims(HUMAN,"human")),"T1",true,true,keys,NOW));
        var valid=sign(key,"at+jwt",claims(HUMAN,"human"));
        assertNull(authorize(valid,"T1",true,true,Map.of("rotated",rotated),NOW));
        assertNotNull(authorize(valid,"T1",true,true,Map.of("current",key,"rotated",rotated),NOW));
        assertNull(authorize(valid,"T1",true,true,keys,NOW.plusSeconds(329)));
        assertNull(authorize(valid,"T1",false,true,keys,NOW));
    }
}
