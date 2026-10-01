package io.wyrmgate.iam.api.administration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdministrationCursorCodecTest {

    @Test
    void cursorIsTenantAndCollectionBoundAndTamperEvident() throws Exception {
        Instant now = Instant.parse("2026-10-01T10:00:00Z");
        SigningKeyProvider keys = keys();
        AdministrationCursorCodec codec =
                new AdministrationCursorCodec(keys, Duration.ofMinutes(15), Clock.fixed(now, ZoneOffset.UTC));
        TenantContext tenant = new TenantContext(UUID.randomUUID());
        AdministrationCursorCodec.Position position =
                new AdministrationCursorCodec.Position(now.minusSeconds(10), UUID.randomUUID());

        String cursor = codec.encode("administrative-grant", tenant, position);

        assertThat(codec.decode(cursor, "administrative-grant", tenant)).isEqualTo(position);
        assertThatThrownBy(() -> codec.decode(
                        cursor, "administrative-delegation", tenant))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode(
                        cursor, "administrative-grant", new TenantContext(UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class);

        String tampered = cursor.substring(0, cursor.length() - 1)
                + (cursor.endsWith("A") ? "B" : "A");
        assertThatThrownBy(() -> codec.decode(tampered, "administrative-grant", tenant))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static SigningKeyProvider keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        SigningKeyMaterial material =
                new SigningKeyMaterial("test-key", "SHA256withRSA", pair.getPublic());
        return new SigningKeyProvider() {
            @Override
            public SigningKeyMaterial currentSigningKey() {
                return material;
            }

            @Override
            public Optional<SigningKeyMaterial> verificationKey(String keyId) {
                return material.keyId().equals(keyId) ? Optional.of(material) : Optional.empty();
            }

            @Override
            public byte[] sign(byte[] payload) {
                try {
                    Signature signer = Signature.getInstance(material.signingAlgorithm());
                    signer.initSign(pair.getPrivate());
                    signer.update(payload);
                    return signer.sign();
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }
        };
    }
}
