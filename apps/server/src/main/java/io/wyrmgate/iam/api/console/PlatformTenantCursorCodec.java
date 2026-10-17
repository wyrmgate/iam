package io.wyrmgate.iam.api.console;

import io.wyrmgate.iam.api.security.ControlPlaneAuthProperties;
import io.wyrmgate.iam.platform.crypto.SigningKeyMaterial;
import io.wyrmgate.iam.platform.crypto.SigningKeyProvider;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Integrity-protected deployment-scoped cursor for the Platform Tenant inventory. */
@Component
final class PlatformTenantCursorCodec {
    record Position(Instant createdAt, UUID id) {}

    private static final String ENVELOPE_VERSION = "v2";
    private static final String PAYLOAD_VERSION = "platform-tenants-p1";
    private static final int MAX_CURSOR_LENGTH = 4096;

    private final SigningKeyProvider signingKeys;
    private final Duration lifetime;
    private final Clock clock;

    @Autowired
    PlatformTenantCursorCodec(
            ObjectProvider<SigningKeyProvider> signingKeys,
            ControlPlaneAuthProperties authProperties,
            @Value("${iam.api.cursor.lifetime:PT15M}") Duration lifetime) {
        this(signingKeys.getIfAvailable(), lifetime, Clock.systemUTC());
        if (authProperties.enabled() && this.signingKeys == null) {
            throw new IllegalStateException(
                    "iam.signing.enabled=true and signing key material are required when control-plane authentication is enabled");
        }
    }

    PlatformTenantCursorCodec(SigningKeyProvider signingKeys, Duration lifetime, Clock clock) {
        this.signingKeys = signingKeys;
        this.lifetime = Objects.requireNonNull(lifetime, "lifetime");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (lifetime.isZero() || lifetime.isNegative() || lifetime.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("platform tenant cursor lifetime must be positive and at most PT24H");
        }
    }

    String encode(Position position) {
        if (position == null) return null;
        return sign(PAYLOAD_VERSION + "\u0000" + clock.instant() + "\u0000"
                + position.createdAt().getEpochSecond() + "\u0000" + position.createdAt().getNano()
                + "\u0000" + position.id());
    }

    Position decode(String cursor) {
        String payload = verifiedPayload(cursor);
        String[] parts = payload.split("\u0000", -1);
        if (parts.length != 5 || !PAYLOAD_VERSION.equals(parts[0])) throw invalid();
        try {
            Instant issuedAt = Instant.parse(parts[1]);
            Instant now = clock.instant();
            if (issuedAt.isAfter(now) || !now.isBefore(issuedAt.plus(lifetime))) throw invalid();
            return new Position(
                    Instant.ofEpochSecond(Long.parseLong(parts[2]), Integer.parseInt(parts[3])),
                    UUID.fromString(parts[4]));
        } catch (RuntimeException invalid) {
            throw invalid(invalid);
        }
    }

    private String sign(String payload) {
        SigningKeyProvider keys = requireKeys();
        SigningKeyMaterial key = keys.currentSigningKey();
        String encoded = encode(payload.getBytes(StandardCharsets.UTF_8));
        String signed = ENVELOPE_VERSION + "." + key.keyId() + "." + encoded;
        return signed + "." + encode(keys.sign(signed.getBytes(StandardCharsets.UTF_8)));
    }

    private String verifiedPayload(String cursor) {
        if (cursor == null || cursor.isBlank() || cursor.length() > MAX_CURSOR_LENGTH) throw invalid();
        String[] envelope = cursor.split("\\.", -1);
        if (envelope.length != 4 || !ENVELOPE_VERSION.equals(envelope[0])) throw invalid();
        SigningKeyMaterial key = requireKeys().verificationKey(envelope[1])
                .orElseThrow(PlatformTenantCursorCodec::invalid);
        String signed = envelope[0] + "." + envelope[1] + "." + envelope[2];
        if (!verify(key, signed.getBytes(StandardCharsets.UTF_8), decodeBase64(envelope[3]))) throw invalid();
        return new String(decodeBase64(envelope[2]), StandardCharsets.UTF_8);
    }

    private SigningKeyProvider requireKeys() {
        if (signingKeys == null) throw new IllegalStateException("cursor signing keys are not configured");
        return signingKeys;
    }

    private static boolean verify(SigningKeyMaterial key, byte[] payload, byte[] signature) {
        try {
            Signature verifier = Signature.getInstance(key.signingAlgorithm());
            verifier.initVerify(key.publicKey());
            verifier.update(payload);
            return verifier.verify(signature);
        } catch (GeneralSecurityException invalid) {
            throw invalid(invalid);
        }
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] decodeBase64(String value) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            if (!encode(decoded).equals(value)) throw invalid();
            return decoded;
        } catch (IllegalArgumentException invalid) {
            throw invalid(invalid);
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid cursor");
    }

    private static IllegalArgumentException invalid(Throwable cause) {
        return new IllegalArgumentException("invalid cursor", cause);
    }
}
