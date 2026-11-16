package io.wyrmgate.iam.api.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.wyrmgate.iam.administration.application.InitialAdminBootstrapService;
import io.wyrmgate.iam.administration.domain.ExternalAuthenticationSubject;
import io.wyrmgate.iam.identity.application.IdentityCommandService;
import io.wyrmgate.iam.identity.domain.IdentityLifecycleState;
import io.wyrmgate.iam.identity.domain.IdentityProfile;
import io.wyrmgate.iam.identity.domain.IdentityType;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import io.wyrmgate.iam.tenancy.persistence.TenantTestFixture;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.test.context.support.DirtiesContextTestExecutionListener;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Real decoder/discovery, HTTP filter chain and persisted Administration authorization. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(listeners = ExternalBearerAuthorityIntegrationTest.Cleanup.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "iam.auth.enabled=true", "iam.auth.audience=fixture-api",
        "iam.auth.token-profile=access-jwt", "iam.idp.enabled=false", "iam.otel.enabled=false",
        "iam.signing.enabled=false", "iam.bootstrap.installation.enabled=false", "iam.bootstrap.web.enabled=false"
})
class ExternalBearerAuthorityIntegrationTest {
    static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:18.4-alpine");
    static final String AUD = "fixture-api", SUBJECT = "synthetic-operator";
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final java.security.KeyPair PAIR;
    static final HttpServer ISSUER;
    static final String ORIGIN;
    static {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            PAIR = generator.generateKeyPair();
            ISSUER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ORIGIN = "http://127.0.0.1:" + ISSUER.getAddress().getPort();
            String jwks = new JWKSet(new RSAKey.Builder((RSAPublicKey) PAIR.getPublic())
                    .keyID("external-fixture").algorithm(JWSAlgorithm.RS256).build()).toString();
            String metadata = JSON.writeValueAsString(Map.of("issuer", ORIGIN, "jwks_uri", ORIGIN + "/jwks",
                    "id_token_signing_alg_values_supported", List.of("RS256")));
            ISSUER.createContext("/.well-known/openid-configuration", x -> respond(x, metadata));
            ISSUER.createContext("/.well-known/oauth-authorization-server", x -> respond(x, metadata));
            ISSUER.createContext("/jwks", x -> respond(x, jwks));
            ISSUER.start();
            DB.start();
        } catch (Exception ex) { throw new ExceptionInInitializerError(ex); }
    }
    static void respond(com.sun.net.httpserver.HttpExchange x, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(200, bytes.length);
        try (var out = x.getResponseBody()) { out.write(bytes); }
        finally { x.close(); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
        r.add("iam.auth.issuer-uri", () -> ORIGIN);
    }
    public static class Cleanup extends AbstractTestExecutionListener {
        @Override public int getOrder() { return DirtiesContextTestExecutionListener.ORDER - 1; }
        @Override public void afterTestClass(TestContext context) {
            try { DB.stop(); } finally { ISSUER.stop(0); }
        }
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired IdGenerator ids;
    @Autowired IdentityCommandService identities;
    @Autowired InitialAdminBootstrapService bootstrap;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    String token(String issuer, String subject, List<String> aud, String typ, Instant expiration, UUID spoofTenant)
            throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).subject(subject).audience(aud)
                .issueTime(Date.from(Instant.now().minusSeconds(120)))
                .claim("roles", List.of("administrator")).claim("scope", "identity:read identity:create")
                .claim("tenant_id", spoofTenant.toString());
        if (expiration != null) claims.expirationTime(Date.from(expiration));
        var header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("external-fixture");
        if (typ != null) header.type(new JOSEObjectType(typ));
        var signed = new SignedJWT(header.build(), claims.build());
        signed.sign(new RSASSASigner(PAIR.getPrivate()));
        return signed.serialize();
    }
    HttpResponse<String> get(String path, String bearer, UUID spoofTenant) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + bearer)
                .header("X-Tenant-Id", spoofTenant.toString()).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void actualSignedBearerAndPersistedAuthorityRejectWrongTypeCrossTenantAndRevokedGrant() throws Exception {
        Instant now = Instant.now().minusSeconds(2);
        var tenants = new TenantTestFixture(jdbc, ids);
        var tenant = new TenantContext(tenants.createActive("Bearer tenant A", now).id());
        var other = new TenantContext(tenants.createActive("Bearer tenant B", now).id());
        var actor = identities.create(tenant, IdentityType.PERSON, new IdentityProfile.PersonProfile(),
                IdentityLifecycleState.ACTIVE, "Bearer operator", now, ids.nextId(), null);
        var foreign = identities.create(other, IdentityType.PERSON, new IdentityProfile.PersonProfile(),
                IdentityLifecycleState.ACTIVE, "Foreign fixture identity", now, ids.nextId(), null);
        var grant = bootstrap.bootstrap(tenant, actor.id(), new ExternalAuthenticationSubject(ORIGIN, SUBJECT),
                now, ids.nextId());
        var until = Instant.now().plusSeconds(300);
        UUID spoof = other.tenantId();
        String good = token(ORIGIN, SUBJECT, List.of(AUD), "at+jwt", until, spoof);
        String own = "/api/v1/identities/" + actor.id();
        assertEquals(200, get(own, good, spoof).statusCode());
        var page = get("/api/v1/identities", good, spoof);
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains(actor.id().toString()));
        assertFalse(page.body().contains(foreign.id().toString()));
        assertEquals(404, get("/api/v1/identities/" + foreign.id(), good, spoof).statusCode());
        assertEquals(401, get(own, token(ORIGIN, SUBJECT, List.of(AUD), "JWT", until, spoof), spoof).statusCode());
        assertEquals(401, get(own, token(ORIGIN, SUBJECT, List.of(AUD), null, until, spoof), spoof).statusCode());
        assertEquals(401, get(own, token(ORIGIN, SUBJECT, List.of("wrong"), "at+jwt", until, spoof), spoof).statusCode());
        assertEquals(401, get(own, token(ORIGIN, SUBJECT, List.of(AUD,"other"), "at+jwt", until, spoof), spoof).statusCode());
        assertEquals(401, get(own, token("https://other.example.invalid", SUBJECT, List.of(AUD), "at+jwt", until, spoof), spoof).statusCode());
        assertEquals(401, get(own, token(ORIGIN, SUBJECT, List.of(AUD), "at+jwt", Instant.now().minusSeconds(300), spoof), spoof).statusCode());
        assertEquals(401, get(own, token(ORIGIN, SUBJECT, List.of(AUD), "at+jwt", null, spoof), spoof).statusCode());
        assertEquals(401, get(own, token(ORIGIN, "unknown-actor", List.of(AUD), "at+jwt", until, spoof), spoof).statusCode());
        // Direct test fixture mutation, not a new production grant API; committed before request.
        assertEquals(1, jdbc.update("UPDATE administration.administrative_grant SET state='REVOKED', revision=revision+1, updated_at=? WHERE tenant_id=? AND id=?",
                java.sql.Timestamp.from(Instant.now()), tenant.tenantId(), grant.administrativeGrantId()));
        assertEquals(403, get(own, good, spoof).statusCode());
        assertEquals("REVOKED", jdbc.queryForObject("SELECT state FROM administration.administrative_grant WHERE tenant_id=? AND id=?",
                String.class, tenant.tenantId(), grant.administrativeGrantId()));
        // Explicit compatibility mode is not tried after strict-profile denial.
        var legacy = new ControlPlaneSecurityConfiguration().controlPlaneJwtDecoder(
                new ControlPlaneAuthProperties(true, ORIGIN, AUD), "legacy");
        assertEquals(SUBJECT, legacy.decode(token(ORIGIN, SUBJECT, List.of(AUD), "JWT", until, spoof)).getSubject());
        assertThrows(IllegalStateException.class, () -> new ControlPlaneSecurityConfiguration().controlPlaneJwtDecoder(
                new ControlPlaneAuthProperties(true, ORIGIN, AUD), "unknown"));
        System.out.println("G3 acceptance: signed HTTP strict profile allow/deny, Tenant spoof ignored, persisted grant revoke denies same bearer, legacy compatibility retained");
    }
}
