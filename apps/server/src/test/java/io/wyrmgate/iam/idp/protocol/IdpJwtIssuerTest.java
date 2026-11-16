package io.wyrmgate.iam.idp.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import io.wyrmgate.iam.catalog.domain.SsoClientScope;
import io.wyrmgate.iam.platform.crypto.SigningKeyMaterial;
import io.wyrmgate.iam.platform.crypto.SigningKeyProvider;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class IdpJwtIssuerTest {

    @Test
    void tokensUseJoseAlgorithmNamesAndDataMinimizedOpaqueSubject() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        SigningKeyProvider provider = provider(pair);
        JsonMapper json = JsonMapper.builder().build();
        IdpJwtIssuer issuer = new IdpJwtIssuer(
                new IdpProtocolProperties(true, "https://idp.example.test"),
                provider,
                json);
        TenantContext tenant = new TenantContext(UUID.randomUUID());
        UUID identityId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-10T02:00:00Z");
        IdpAuthorizationCode code = code(identityId, now);

        IdpTokenIssuer.TokenPair issued = issuer.issue(tenant, code, now);

        JsonNode idHeader = decodePart(json, issued.idToken(), 0);
        JsonNode idClaims = decodePart(json, issued.idToken(), 1);
        JsonNode accessClaims = decodePart(json, issued.accessToken(), 1);

        assertThat(idHeader.path("alg").asText()).isEqualTo("RS256");
        assertThat(idHeader.path("kid").asText()).isEqualTo("kid-1");
        assertThat(idClaims.path("iss").asText()).isEqualTo("https://idp.example.test");
        assertThat(idClaims.path("aud").asText()).isEqualTo(code.clientId());
        assertThat(idClaims.path("nonce").asText()).isEqualTo(code.nonce());
        assertThat(idClaims.path("sub").asText())
                .isNotBlank()
                .isNotEqualTo(identityId.toString())
                .isNotEqualTo(tenant.tenantId().toString());

        assertThat(accessClaims.path("scope").asText()).isEqualTo("email openid profile");
        for (String prohibited : Set.of(
                "tenant", "tenant_id", "identity_id", "principal_id", "roles",
                "administrativeRoles", "assignments", "credentials", "secretReference")) {
            assertThat(idClaims.has(prohibited)).isFalse();
            assertThat(accessClaims.has(prohibited)).isFalse();
        }

        verifySignature(pair, issued.idToken());
        verifySignature(pair, issued.accessToken());
    }

    @Test
    void allSupportedRsaAlgorithmsAndRotationUseSamePublicationPolicy() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair=generator.generateKeyPair();
        var previous=new SigningKeyMaterial("old","SHA256withRSA",generator.generateKeyPair().getPublic());
        var now=Instant.parse("2026-11-16T00:00:00Z");
        for (String algorithm : java.util.List.of("SHA256withRSA","SHA384withRSA","SHA512withRSA")) {
            var current=new SigningKeyMaterial("current",algorithm,pair.getPublic());
            var published=new java.util.concurrent.atomic.AtomicReference<>(java.util.List.of(previous,current));
            SigningKeyProvider provider=new SigningKeyProvider(){
                public SigningKeyMaterial currentSigningKey(){return current;}
                public Optional<SigningKeyMaterial> verificationKey(String kid){return published.get().stream().filter(k->k.keyId().equals(kid)).findFirst();}
                public java.util.List<SigningKeyMaterial> verificationKeys(){return published.get();}
                public byte[] sign(byte[] payload){
                    try {var s=Signature.getInstance(algorithm);s.initSign(pair.getPrivate());s.update(payload);return s.sign();}
                    catch(Exception e){throw new IllegalStateException(e);}
                }
            };
            var props=new IdpProtocolProperties(true,"https://idp.example.test");
            var issuer=new IdpJwtIssuer(props,provider,JsonMapper.builder().build());
            var controller=new IdpProtocolController(props,provider);
            var token=issuer.issue(new TenantContext(UUID.randomUUID()),code(UUID.randomUUID(),now),now);
            var jwt=com.nimbusds.jwt.SignedJWT.parse(token.accessToken());
            assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo(IdpJoseAlgorithms.joseName(algorithm));
            assertThat(jwt.verify(new com.nimbusds.jose.crypto.RSASSAVerifier((java.security.interfaces.RSAPublicKey)pair.getPublic()))).isTrue();
            assertThat(controller.jwks().getBody().toString()).contains("old","current");
            // Duplicate *retained* IDs used to allow issuance even when JWKS was unusable.
            published.set(java.util.List.of(current,previous,previous));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,()->issuer.issue(
                    new TenantContext(UUID.randomUUID()),code(UUID.randomUUID(),now),now));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,controller::jwks);
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,controller::discovery);
            published.set(java.util.List.of(current));
            assertThat(controller.jwks().getBody().toString()).doesNotContain("old");
            assertThat(issuer.issue(new TenantContext(UUID.randomUUID()),code(UUID.randomUUID(),now),now).accessToken()).isNotBlank();
        }
    }

    @Test
    void weakCurrentOrRetainedRsaKeysFailStartupAndEveryPublicationPath() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var strongPair = generator.generateKeyPair();
        generator.initialize(1024);
        var weakPair = generator.generateKeyPair();
        var props = new IdpProtocolProperties(true, "https://idp.example.test");
        var now = Instant.parse("2026-11-16T00:00:00Z");
        for (String algorithm : java.util.List.of("SHA256withRSA", "SHA384withRSA", "SHA512withRSA")) {
            var strong = new SigningKeyMaterial("strong", algorithm, strongPair.getPublic());
            var weak = new SigningKeyMaterial("weak", algorithm, weakPair.getPublic());
            var current = new java.util.concurrent.atomic.AtomicReference<>(strong);
            var published = new java.util.concurrent.atomic.AtomicReference<>(java.util.List.of(strong));
            var signCalls = new java.util.concurrent.atomic.AtomicInteger();
            SigningKeyProvider provider = new SigningKeyProvider() {
                public SigningKeyMaterial currentSigningKey() { return current.get(); }
                public Optional<SigningKeyMaterial> verificationKey(String kid) {
                    return published.get().stream().filter(k -> k.keyId().equals(kid)).findFirst();
                }
                public java.util.List<SigningKeyMaterial> verificationKeys() { return published.get(); }
                public byte[] sign(byte[] payload) {
                    signCalls.incrementAndGet();
                    try {
                        var signature = Signature.getInstance(algorithm);
                        signature.initSign(strongPair.getPrivate());
                        signature.update(payload);
                        return signature.sign();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
            };
            var controller = new IdpProtocolController(props, provider);
            var issuer = new IdpJwtIssuer(props, provider, JsonMapper.builder().build());
            var tenant = new TenantContext(UUID.randomUUID());
            var authorizationCode = code(UUID.randomUUID(), now);
            assertThat(issuer.issue(tenant, authorizationCode, now).accessToken()).isNotBlank();
            assertThat(controller.discovery().getBody()).isNotNull();
            assertThat(controller.jwks().getBody()).isNotNull();
            int before = signCalls.get();

            // Weak retained material must invalidate the entire published set, even with a strong current key.
            published.set(java.util.List.of(strong, weak));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> new IdpProtocolController(props, provider));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, controller::discovery);
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, controller::jwks);
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> issuer.issue(tenant, authorizationCode, now));
            assertThat(signCalls.get()).isEqualTo(before);

            // A weak current key also fails every path before signing.
            current.set(weak);
            published.set(java.util.List.of(weak, strong));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> new IdpProtocolController(props, provider));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, controller::discovery);
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, controller::jwks);
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> issuer.issue(tenant, authorizationCode, now));
            assertThat(signCalls.get()).isEqualTo(before);

            // Restoring a valid set restores issuance, discovery and JWKS.
            current.set(strong);
            published.set(java.util.List.of(strong));
            assertThat(issuer.issue(tenant, authorizationCode, now).accessToken()).isNotBlank();
            assertThat(controller.discovery().getBody()).isNotNull();
            assertThat(controller.jwks().getBody()).isNotNull();
        }
    }

    private static IdpAuthorizationCode code(UUID identityId, Instant now) {
        return new IdpAuthorizationCode(
                UUID.randomUUID(),
                UUID.randomUUID(),
                3,
                UUID.randomUUID(),
                "wc_1234567890abcdef1234567890abcdef",
                UUID.randomUUID(),
                UUID.randomUUID(),
                identityId,
                "a".repeat(64),
                "https://app.example.test/callback",
                Set.of(SsoClientScope.OPENID, SsoClientScope.PROFILE, SsoClientScope.EMAIL),
                "A".repeat(43),
                "nonce-1234567890abcdef",
                now.minusSeconds(60),
                now.minusSeconds(30),
                now.plusSeconds(270),
                now);
    }

    private static SigningKeyProvider provider(KeyPair pair) {
        SigningKeyMaterial material =
                new SigningKeyMaterial("kid-1", "SHA256withRSA", pair.getPublic());
        return new SigningKeyProvider() {
            @Override
            public SigningKeyMaterial currentSigningKey() {
                return material;
            }

            @Override
            public Optional<SigningKeyMaterial> verificationKey(String keyId) {
                return "kid-1".equals(keyId) ? Optional.of(material) : Optional.empty();
            }

            @Override
            public byte[] sign(byte[] payload) {
                try {
                    Signature signature = Signature.getInstance("SHA256withRSA");
                    signature.initSign(pair.getPrivate());
                    signature.update(payload);
                    return signature.sign();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }
        };
    }

    private static JsonNode decodePart(JsonMapper json, String jwt, int part) throws Exception {
        String[] pieces = jwt.split("\\.");
        return json.readTree(Base64.getUrlDecoder().decode(pieces[part]));
    }

    private static void verifySignature(KeyPair pair, String jwt) throws Exception {
        String[] pieces = jwt.split("\\.");
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(pair.getPublic());
        verifier.update((pieces[0] + "." + pieces[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(pieces[2]))).isTrue();
    }
}
