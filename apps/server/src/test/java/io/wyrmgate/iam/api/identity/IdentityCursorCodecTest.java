package io.wyrmgate.iam.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.wyrmgate.iam.identity.application.IdentityQueryModels.CanonicalAttributePagePosition;
import io.wyrmgate.iam.identity.application.IdentityQueryModels.IdentityPagePosition;
import io.wyrmgate.iam.platform.crypto.SigningKeyMaterial;
import io.wyrmgate.iam.platform.crypto.SigningKeyProvider;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentityCursorCodecTest {

    private static final Instant NOW = Instant.parse("2026-09-21T03:30:00Z");
    private static final Duration LIFETIME = Duration.ofMinutes(15);

    @Test
    void identityCursorRejectsTamperingAndCrossTenantReuse() throws Exception {
        TestProvider provider = TestProvider.single("key-a");
        IdentityCursorCodec codec = codec(provider, NOW);
        TenantContext tenant = tenant();
        IdentityPagePosition position =
                new IdentityPagePosition(Instant.parse("2026-09-20T10:15:30.123456789Z"), UUID.randomUUID());

        String cursor = codec.encodeIdentity(tenant, position);

        assertThat(codec.decodeIdentity(cursor, tenant)).isEqualTo(position);
        String tampered = cursor.substring(0, cursor.length() - 1)
                + (cursor.endsWith("A") ? "B" : "A");
        assertThatThrownBy(() -> codec.decodeIdentity(tampered, tenant))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid cursor");
        assertThatThrownBy(() -> codec.decodeIdentity(cursor, new TenantContext(UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid cursor");
    }

    @Test
    void canonicalCursorIsBoundToIdentityResource() throws Exception {
        TestProvider provider = TestProvider.single("key-a");
        IdentityCursorCodec codec = codec(provider, NOW);
        TenantContext tenant = tenant();
        UUID identityId = UUID.randomUUID();
        CanonicalAttributePagePosition position =
                new CanonicalAttributePagePosition("departmentCode", UUID.randomUUID());

        String cursor = codec.encodeCanonical(tenant, identityId, position);

        assertThat(codec.decodeCanonical(cursor, tenant, identityId)).isEqualTo(position);
        assertThatThrownBy(() -> codec.decodeCanonical(cursor, tenant, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid cursor");
    }

    @Test
    void unsignedV1CursorIsRejected() throws Exception {
        TestProvider provider = TestProvider.single("key-a");
        TenantContext tenant = tenant();
        String oldCursor = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("v1\u00001791234000\u00000\u0000" + UUID.randomUUID())
                        .getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> codec(provider, NOW).decodeIdentity(oldCursor, tenant))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid cursor");
    }

    @Test
    void cursorExpiresAtConfiguredLifetime() throws Exception {
        TestProvider provider = TestProvider.single("key-a");
        TenantContext tenant = tenant();
        IdentityPagePosition position =
                new IdentityPagePosition(Instant.parse("2026-09-20T10:15:30Z"), UUID.randomUUID());
        String cursor = codec(provider, NOW).encodeIdentity(tenant, position);

        assertThatThrownBy(() -> codec(provider, NOW.plus(LIFETIME)).decodeIdentity(cursor, tenant))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid cursor");
    }

    @Test
    void retiredPublicKeyCanVerifyCursorAcrossRotation() throws Exception {
        TestProvider oldProvider = TestProvider.single("key-old");
        TenantContext tenant = tenant();
        IdentityPagePosition position =
                new IdentityPagePosition(Instant.parse("2026-09-20T10:15:30Z"), UUID.randomUUID());
        String cursor = codec(oldProvider, NOW).encodeIdentity(tenant, position);

        TestProvider rotated = TestProvider.rotated("key-new", oldProvider);
        assertThat(codec(rotated, NOW.plusSeconds(30)).decodeIdentity(cursor, tenant)).isEqualTo(position);
    }

    private static IdentityCursorCodec codec(SigningKeyProvider provider, Instant now) {
        return new IdentityCursorCodec(
                provider,
                LIFETIME,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private static TenantContext tenant() {
        return new TenantContext(UUID.randomUUID());
    }

    private static final class TestProvider implements SigningKeyProvider {
        private final KeyPair currentPair;
        private final SigningKeyMaterial current;
        private final Map<String, SigningKeyMaterial> verification;

        private TestProvider(
                KeyPair currentPair,
                SigningKeyMaterial current,
                Map<String, SigningKeyMaterial> verification) {
            this.currentPair = currentPair;
            this.current = current;
            this.verification = Map.copyOf(verification);
        }

        static TestProvider single(String keyId) throws Exception {
            KeyPair pair = keyPair();
            SigningKeyMaterial material =
                    new SigningKeyMaterial(keyId, "SHA256withRSA", pair.getPublic());
            return new TestProvider(pair, material, Map.of(keyId, material));
        }

        static TestProvider rotated(String keyId, TestProvider previous) throws Exception {
            KeyPair pair = keyPair();
            SigningKeyMaterial material =
                    new SigningKeyMaterial(keyId, "SHA256withRSA", pair.getPublic());
            Map<String, SigningKeyMaterial> keys = new LinkedHashMap<>(previous.verification);
            keys.put(keyId, material);
            return new TestProvider(pair, material, keys);
        }

        @Override
        public SigningKeyMaterial currentSigningKey() {
            return current;
        }

        @Override
        public Optional<SigningKeyMaterial> verificationKey(String keyId) {
            return Optional.ofNullable(verification.get(keyId));
        }

        @Override
        public byte[] sign(byte[] payload) {
            try {
                Signature signature = Signature.getInstance(current.signingAlgorithm());
                signature.initSign(currentPair.getPrivate());
                signature.update(payload);
                return signature.sign();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }

        private static KeyPair keyPair() throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        }
    }
}
