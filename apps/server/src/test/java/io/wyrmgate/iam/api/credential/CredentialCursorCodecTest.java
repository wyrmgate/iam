package io.wyrmgate.iam.api.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.wyrmgate.iam.credential.application.CredentialQueryModels.CredentialPosition;
import io.wyrmgate.iam.credential.application.CredentialQueryModels.RotationPosition;
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

class CredentialCursorCodecTest {

    private static final Instant NOW =
            Instant.parse("2026-09-29T14:00:00Z");

    @Test
    void credentialCursorIsSignedAndPrincipalBound() {
        Fixture fixture = fixture(NOW);
        TenantContext tenant =
                new TenantContext(UUID.randomUUID());
        UUID principal = UUID.randomUUID();
        CredentialPosition position =
                new CredentialPosition(
                        NOW.minusSeconds(10),
                        UUID.randomUUID());

        String cursor = fixture.codec().encodeCredentials(
                tenant, principal, position);

        assertThat(fixture.codec().decodeCredentials(
                cursor, tenant, principal))
                .isEqualTo(position);
        assertThatThrownBy(() ->
                fixture.codec().decodeCredentials(
                        cursor,
                        tenant,
                        UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);

        String tampered =
                cursor.substring(0, cursor.length() - 1)
                        + (cursor.endsWith("A") ? "B" : "A");
        assertThatThrownBy(() ->
                fixture.codec().decodeCredentials(
                        tampered, tenant, principal))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rotationCursorIsTenantAndCredentialBound() {
        Fixture fixture = fixture(NOW);
        TenantContext tenant =
                new TenantContext(UUID.randomUUID());
        UUID oldCredentialId = UUID.randomUUID();
        RotationPosition position =
                new RotationPosition(
                        NOW.minusSeconds(5),
                        UUID.randomUUID());

        String cursor = fixture.codec().encodeRotations(
                tenant, oldCredentialId, position);

        assertThat(fixture.codec().decodeRotations(
                cursor, tenant, oldCredentialId))
                .isEqualTo(position);
        assertThatThrownBy(() ->
                fixture.codec().decodeRotations(
                        cursor,
                        new TenantContext(UUID.randomUUID()),
                        oldCredentialId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                fixture.codec().decodeRotations(
                        cursor,
                        tenant,
                        UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void expiredCursorIsRejected() {
        Fixture fixture = fixture(NOW);
        TenantContext tenant =
                new TenantContext(UUID.randomUUID());
        UUID principal = UUID.randomUUID();
        String cursor = fixture.codec().encodeCredentials(
                tenant,
                principal,
                new CredentialPosition(
                        NOW.minusSeconds(1),
                        UUID.randomUUID()));

        CredentialCursorCodec expired =
                new CredentialCursorCodec(
                        fixture.keys(),
                        Duration.ofMinutes(15),
                        Clock.fixed(
                                NOW.plus(Duration.ofMinutes(16)),
                                ZoneOffset.UTC));

        assertThatThrownBy(() ->
                expired.decodeCredentials(
                        cursor, tenant, principal))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Fixture fixture(Instant now) {
        try {
            KeyPairGenerator generator =
                    KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            SigningKeyMaterial material =
                    new SigningKeyMaterial(
                            "credential-api-test",
                            "SHA256withRSA",
                            pair.getPublic());
            SigningKeyProvider provider =
                    new SigningKeyProvider() {
                        @Override
                        public SigningKeyMaterial
                                currentSigningKey() {
                            return material;
                        }

                        @Override
                        public Optional<SigningKeyMaterial>
                                verificationKey(String keyId) {
                            return material.keyId().equals(keyId)
                                    ? Optional.of(material)
                                    : Optional.empty();
                        }

                        @Override
                        public byte[] sign(byte[] payload) {
                            try {
                                Signature signature =
                                        Signature.getInstance(
                                                material.signingAlgorithm());
                                signature.initSign(pair.getPrivate());
                                signature.update(payload);
                                return signature.sign();
                            } catch (Exception error) {
                                throw new IllegalStateException(error);
                            }
                        }
                    };
            return new Fixture(
                    provider,
                    new CredentialCursorCodec(
                            provider,
                            Duration.ofMinutes(15),
                            Clock.fixed(now, ZoneOffset.UTC)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private record Fixture(
            SigningKeyProvider keys,
            CredentialCursorCodec codec) {}
}
