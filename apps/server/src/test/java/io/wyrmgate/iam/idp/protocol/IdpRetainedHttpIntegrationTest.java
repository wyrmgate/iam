package io.wyrmgate.iam.idp.protocol;

import io.wyrmgate.iam.idp.session.ConsoleLoginRouteQuery;
import io.wyrmgate.iam.installation.InstallationBootstrapService;
import io.wyrmgate.iam.platform.crypto.SigningKeyMaterial;
import io.wyrmgate.iam.platform.crypto.SigningKeyProvider;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Actual local embedded server, V42 fresh installation and security filter chains. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "server.address=127.0.0.1","iam.auth.enabled=false","iam.idp.enabled=true",
    "iam.idp.issuer=https://iam.example.invalid","iam.idp.federation.enabled=false",
    "iam.signing.enabled=false","iam.otel.enabled=false",
    "iam.bootstrap.installation.enabled=true","iam.bootstrap.installation.tenant-display-name=G3 fixture",
    "iam.bootstrap.installation.identity-display-name=G3 operator","iam.bootstrap.installation.username=g3-fixture",
    "iam.bootstrap.installation.console-redirect-uri=https://console.example.invalid/callback",
    "iam.bootstrap.web.enabled=false"
})
@Import(IdpRetainedHttpIntegrationTest.Keys.class)
class IdpRetainedHttpIntegrationTest {
    static final PostgreSQLContainer DB=new PostgreSQLContainer("postgres:18.4-alpine");
    static final String PASSWORD="Fixture-"+UUID.randomUUID()+"-A9!";
    static {DB.start();}
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",DB::getJdbcUrl);
        r.add("spring.datasource.username",DB::getUsername);
        r.add("spring.datasource.password",DB::getPassword);
        r.add("iam.bootstrap.installation.password",()->PASSWORD);
    }
    @AfterAll static void stop(){DB.stop();}
    @LocalServerPort int port;
    @Autowired InstallationBootstrapService bootstrap;
    @Autowired ConsoleLoginRouteQuery console;
    @Autowired Flyway flyway;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    final JsonMapper json=JsonMapper.builder().build();

    @TestConfiguration(proxyBeanMethods=false)
    static class Keys {
        @Bean SigningKeyProvider fixtureSigningKeys() throws Exception{
            var gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);
            var pair=gen.generateKeyPair();
            var material=new SigningKeyMaterial("g3-fixture-key","SHA256withRSA",pair.getPublic());
            return new SigningKeyProvider(){
                public SigningKeyMaterial currentSigningKey(){return material;}
                public Optional<SigningKeyMaterial> verificationKey(String kid){return material.keyId().equals(kid)?Optional.of(material):Optional.empty();}
                public byte[] sign(byte[] input){
                    try{var s=Signature.getInstance(material.signingAlgorithm());s.initSign(pair.getPrivate());s.update(input);return s.sign();}
                    catch(GeneralSecurityException e){throw new IllegalStateException("fixture signing unavailable");}
                }
            };
        }
    }

    HttpResponse<String> call(String method,String path,String body,String contentType,String cookies,String csrf) throws Exception{
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));
        if(cookies!=null)b.header("Cookie",cookies);
        if(csrf!=null)b.header("X-Wyrmgate-CSRF",csrf);
        if(contentType!=null)b.header("Content-Type",contentType);
        b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    static String cookie(HttpResponse<?> r,String name){
        return r.headers().allValues("Set-Cookie").stream().filter(v->v.startsWith(name+"="))
            .map(v->v.substring(0,v.indexOf(';')>0?v.indexOf(';'):v.length())).findFirst().orElseThrow();
    }
    static String value(String cookie){return cookie.substring(cookie.indexOf('=')+1);}
    static String enc(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}
    static String form(Map<String,String> fields){return fields.entrySet().stream().map(e->enc(e.getKey())+"="+enc(e.getValue())).collect(java.util.stream.Collectors.joining("&"));}

    @Test void sealedBootRealHttpLoginPkceCsrfLogoutAndClosedUnknownGrant() throws Exception{
        assertTrue(bootstrap.isSealed());
        assertEquals("42",flyway.info().current().getVersion().getVersion());
        assertEquals(401,call("GET","/api/v1/console/tenant-context",null,null,null,null).statusCode());
        var discovery=call("GET","/.well-known/openid-configuration",null,null,null,null);
        assertEquals(200,discovery.statusCode());
        assertEquals("authorization_code",json.readTree(discovery.body()).path("grant_types_supported").get(0).asText());
        assertEquals(200,call("GET","/oauth2/jwks",null,null,null,null).statusCode());

        String loginBody=json.writeValueAsString(Map.of("principalKey","g3-fixture","password",PASSWORD));
        assertEquals(403,call("POST","/api/auth/login",loginBody,"application/json",null,null).statusCode());
        var route=call("GET","/api/auth/login-route",null,null,null,null);
        assertEquals(200,route.statusCode());
        String pre= cookie(route,"__Host-wyrmgate_csrf");
        var login=call("POST","/api/auth/login",loginBody,"application/json",pre,value(pre));
        assertEquals(200,login.statusCode());
        String session=cookie(login,"__Host-wyrmgate_sso"),csrf=cookie(login,"__Host-wyrmgate_csrf");
        String cookies=session+"; "+csrf;
        assertTrue(login.headers().allValues("Set-Cookie").stream().anyMatch(v->v.startsWith("__Host-wyrmgate_sso=")&&v.contains("Secure")&&v.contains("HttpOnly")));
        var context=call("GET","/api/v1/console/tenant-context",null,null,cookies,null);
        assertEquals(200,context.statusCode());
        assertEquals("G3 fixture",json.readTree(context.body()).path("displayName").asText());

        String client=console.resolve().orElseThrow().clientId(), redirect="https://console.example.invalid/callback";
        String verifier="a".repeat(43);
        String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(IdpJwtIssuer.sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
        var auth=call("GET","/oauth2/authorize?"+form(Map.of("response_type","code","client_id",client,"redirect_uri",redirect,
            "scope","openid","state","fixture-state-123456","code_challenge",challenge,"code_challenge_method","S256")),null,null,cookies,null);
        assertEquals(302,auth.statusCode());
        String location=auth.headers().firstValue("Location").orElseThrow();
        var query=org.springframework.web.util.UriComponentsBuilder.fromUriString(location).build().getQueryParams();
        assertEquals("fixture-state-123456",query.getFirst("state"));
        String code=query.getFirst("code");assertNotNull(code);
        String tokenBody=form(Map.of("grant_type","authorization_code","code",code,"client_id",client,"redirect_uri",redirect,"code_verifier",verifier));
        var token=call("POST","/oauth2/token",tokenBody,"application/x-www-form-urlencoded",null,null);
        assertEquals(200,token.statusCode());
        var claims=json.readTree(token.body());
        assertEquals("Bearer",claims.path("token_type").asText());
        assertTrue(claims.path("access_token").isTextual()&&claims.path("id_token").isTextual());
        assertEquals(400,call("POST","/oauth2/token",tokenBody,"application/x-www-form-urlencoded",null,null).statusCode());
        var unsupported=call("POST","/oauth2/token","grant_type=client_credentials","application/x-www-form-urlencoded",null,null);
        assertEquals(400,unsupported.statusCode());
        assertEquals("unsupported_grant_type",json.readTree(unsupported.body()).path("error").asText());

        assertEquals(403,call("POST","/api/auth/logout",null,null,cookies,null).statusCode());
        assertEquals(204,call("POST","/api/auth/logout",null,null,cookies,value(csrf)).statusCode());
        assertEquals(401,call("GET","/api/v1/console/tenant-context",null,null,cookies,null).statusCode());
    }
}
