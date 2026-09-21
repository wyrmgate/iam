package io.wyrmgate.iam.api.identity;

import io.wyrmgate.iam.identity.application.IdentityQueryModels.CanonicalAttributePagePosition;
import io.wyrmgate.iam.identity.application.IdentityQueryModels.IdentityPagePosition;
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

final class IdentityCursorCodec {

    private static final String ENVELOPE_VERSION = "v2";
    private static final String PAYLOAD_VERSION = "p1";
    private static final String IDENTITY_KIND = "identity";
    private static final String CANONICAL_KIND = "canonical-attribute";
    private static final int MAX_CURSOR_LENGTH = 4096;

    private final SigningKeyProvider signingKeys;
    private final Duration lifetime;
    private final Clock clock;

    IdentityCursorCodec(SigningKeyProvider signingKeys, Duration lifetime, Clock clock) {
        this.signingKeys = signingKeys;
        this.lifetime = Objects.requireNonNull(lifetime, "lifetime");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (lifetime.isZero() || lifetime.isNegative()) {
            throw new IllegalArgumentException("lifetime must be positive");
        }
    }

    String encodeIdentity(TenantContext tenant, IdentityPagePosition position) {
        if (position == null) return null;
        return sign(payload(
                IDENTITY_KIND,
                tenant.tenantId().toString(),
                clock.instant().toString(),
                Long.toString(position.createdAt().getEpochSecond()),
                Integer.toString(position.createdAt().getNano()),
                position.id().toString()));
    }

    IdentityPagePosition decodeIdentity(String cursor, TenantContext tenant) {
        String[] parts = verifiedPayload(cursor);
        if (parts.length != 7
                || !PAYLOAD_VERSION.equals(parts[0])
                || !IDENTITY_KIND.equals(parts[1])
                || !tenant.tenantId().toString().equals(parts[2])) {
            throw invalid();
        }
        validateIssuedAt(parts[3]);
        try {
            return new IdentityPagePosition(
                    Instant.ofEpochSecond(Long.parseLong(parts[4]), Integer.parseInt(parts[5])),
                    UUID.fromString(parts[6]));
        } catch (RuntimeException invalid) {
            throw invalid(invalid);
        }
    }

    String encodeCanonical(
            TenantContext tenant,
            UUID identityId,
            CanonicalAttributePagePosition position) {
        if (position == null) return null;
        return sign(payload(
                CANONICAL_KIND,
                tenant.tenantId().toString(),
                identityId.toString(),
                clock.instant().toString(),
                position.key(),
                position.definitionId().toString()));
    }

    CanonicalAttributePagePosition decodeCanonical(
            String cursor,
            TenantContext tenant,
            UUID identityId) {
        String[] parts = verifiedPayload(cursor);
        if (parts.length != 7
                || !PAYLOAD_VERSION.equals(parts[0])
                || !CANONICAL_KIND.equals(parts[1])
                || !tenant.tenantId().toString().equals(parts[2])
                || !identityId.toString().equals(parts[3])) {
            throw invalid();
        }
        validateIssuedAt(parts[4]);
        try {
            return new CanonicalAttributePagePosition(parts[5], UUID.fromString(parts[6]));
        } catch (RuntimeException invalid) {
            throw invalid(invalid);
        }
    }

    private String sign(String payload) {
        SigningKeyProvider keys = requireSigningKeys();
        SigningKeyMaterial key = keys.currentSigningKey();
        if (!key.keyId().matches("[A-Za-z0-9_-]{1,100}")) {
            throw new IllegalStateException("active signing key ID is not cursor-safe");
        }
        String encodedPayload = encode(payload.getBytes(StandardCharsets.UTF_8));
        String signedValue = ENVELOPE_VERSION + "." + key.keyId() + "." + encodedPayload;
        String signature = encode(keys.sign(signedValue.getBytes(StandardCharsets.UTF_8)));
        return signedValue + "." + signature;
    }

    private String[] verifiedPayload(String cursor) {
        if (cursor == null || cursor.isBlank() || cursor.length() > MAX_CURSOR_LENGTH) {
            throw invalid();
        }
        String[] envelope = cursor.split("\\.", -1);
        if (envelope.length != 4 || !ENVELOPE_VERSION.equals(envelope[0])) {
            throw invalid();
        }
        SigningKeyProvider keys = requireSigningKeys();
        SigningKeyMaterial key = keys.verificationKey(envelope[1]).orElseThrow(IdentityCursorCodec::invalid);
        byte[] signature = decode(envelope[3]);
        String signedValue = envelope[0] + "." + envelope[1] + "." + envelope[2];
        if (!verify(key, signedValue.getBytes(StandardCharsets.UTF_8), signature)) {
            throw invalid();
        }
        String payload = new String(decode(envelope[2]), StandardCharsets.UTF_8);
        return payload.split("\u0000", -1);
    }

    private void validateIssuedAt(String value) {
        try {
            Instant issuedAt = Instant.parse(value);
            Instant now = clock.instant();
            if (issuedAt.isAfter(now) || !now.isBefore(issuedAt.plus(lifetime))) {
                throw invalid();
            }
        } catch (RuntimeException invalid) {
            if (invalid instanceof IllegalArgumentException argument) {
                throw argument;
            }
            throw invalid(invalid);
        }
    }

    private static boolean verify(SigningKeyMaterial key, byte[] payload, byte[] signatureBytes) {
        try {
            Signature verifier = Signature.getInstance(key.signingAlgorithm());
            verifier.initVerify(key.publicKey());
            verifier.update(payload);
            return verifier.verify(signatureBytes);
        } catch (GeneralSecurityException invalid) {
            throw invalid(invalid);
        }
    }

    private static String payload(String... parts) {
        for (String part : parts) {
            if (part == null || part.indexOf('\u0000') >= 0) {
                throw new IllegalArgumentException("cursor payload field is invalid");
            }
        }
        return PAYLOAD_VERSION + "\u0000" + String.join("\u0000", parts);
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] decode(String value) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            if (!encode(decoded).equals(value)) {
                throw invalid();
            }
            return decoded;
        } catch (IllegalArgumentException invalid) {
            throw invalid(invalid);
        }
    }

    private SigningKeyProvider requireSigningKeys() {
        if (signingKeys == null) {
            throw new IllegalStateException("cursor signing keys are not configured");
        }
        return signingKeys;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid cursor");
    }

    private static IllegalArgumentException invalid(Throwable cause) {
        return new IllegalArgumentException("invalid cursor", cause);
    }
}
