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
