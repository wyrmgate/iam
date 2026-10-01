package io.wyrmgate.iam.api.administration;

import io.wyrmgate.iam.platform.crypto.SigningKeyMaterial;
import io.wyrmgate.iam.platform.crypto.SigningKeyProvider;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

final class AdministrationCursorCodec {
    record Position(Instant createdAt, UUID id) {}

    private static final String ENVELOPE_VERSION = "v2";
    private static final String PAYLOAD_VERSION = "p1";
    private static final int MAX_CURSOR_LENGTH = 4096;

    private final SigningKeyProvider signingKeys;
    private final Duration lifetime;
    private final Clock clock;

    AdministrationCursorCodec(SigningKeyProvider signingKeys, Duration lifetime, Clock clock) {
        this.signingKeys = signingKeys;
        this.lifetime = Objects.requireNonNull(lifetime, "lifetime");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (lifetime.isZero() || lifetime.isNegative() || lifetime.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("administration cursor lifetime must be positive and at most PT24H");
        }
    }

    String encode(String kind, TenantContext tenant, Position position) {
        if (position == null) return null;
        return sign(payload(
                kind, tenant.tenantId().toString(), clock.instant().toString(),
                Long.toString(position.createdAt().getEpochSecond()),
                Integer.toString(position.createdAt().getNano()), position.id().toString()));
    }

    Position decode(String cursor, String kind, TenantContext tenant) {
        String[] parts = verifiedPayload(cursor);
        if (parts.length != 7
                || !PAYLOAD_VERSION.equals(parts[0])
                || !kind.equals(parts[1])
                || !tenant.tenantId().toString().equals(parts[2])) throw invalid();
        validateIssuedAt(parts[3]);
        try {
            return new Position(
                    Instant.ofEpochSecond(Long.parseLong(parts[4]), Integer.parseInt(parts[5])),
                    UUID.fromString(parts[6]));
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

    private String[] verifiedPayload(String cursor) {
        if (cursor == null || cursor.isBlank() || cursor.length() > MAX_CURSOR_LENGTH) throw invalid();
        String[] envelope = cursor.split("\\.", -1);
        if (envelope.length != 4 || !ENVELOPE_VERSION.equals(envelope[0])) throw invalid();
        SigningKeyMaterial key = requireKeys().verificationKey(envelope[1]).orElseThrow(AdministrationCursorCodec::invalid);
        String signed = envelope[0] + "." + envelope[1] + "." + envelope[2];
        if (!verify(key, signed.getBytes(StandardCharsets.UTF_8), decode(envelope[3]))) throw invalid();
        return new String(decode(envelope[2]), StandardCharsets.UTF_8).split("\u0000", -1);
    }

    private void validateIssuedAt(String value) {
        Instant issuedAt = Instant.parse(value);
        Instant now = clock.instant();
        if (issuedAt.isAfter(now) || !now.isBefore(issuedAt.plus(lifetime))) throw invalid();
    }

    private static String payload(String... values) {
        for (String value : values) if (value == null || value.indexOf('\u0000') >= 0) throw invalid();
        return PAYLOAD_VERSION + "\u0000" + String.join("\u0000", values);
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

    private static byte[] decode(String value) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            if (!encode(decoded).equals(value)) throw invalid();
            return decoded;
        } catch (IllegalArgumentException invalid) {
            throw invalid(invalid);
        }
    }

    private SigningKeyProvider requireKeys() {
        if (signingKeys == null) throw new IllegalStateException("cursor signing keys are not configured");
        return signingKeys;
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid cursor"); }
    private static IllegalArgumentException invalid(Throwable cause) { return new IllegalArgumentException("invalid cursor", cause); }
}
