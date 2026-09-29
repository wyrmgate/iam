package io.wyrmgate.iam.api.credential;

import io.wyrmgate.iam.credential.application.CredentialQueryModels.CredentialPosition;
import io.wyrmgate.iam.credential.application.CredentialQueryModels.RotationPosition;
import io.wyrmgate.iam.credential.domain.CredentialModels.CredentialState;
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

final class CredentialCursorCodec {

    private static final String ENVELOPE_VERSION = "v2";
    private static final String PAYLOAD_VERSION = "p1";
    private static final int MAX_CURSOR_LENGTH = 4096;

    private final SigningKeyProvider signingKeys;
    private final Duration lifetime;
    private final Clock clock;

    CredentialCursorCodec(
            SigningKeyProvider signingKeys,
            Duration lifetime,
            Clock clock) {
        this.signingKeys = signingKeys;
        this.lifetime = Objects.requireNonNull(lifetime, "lifetime");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (lifetime.isZero()
                || lifetime.isNegative()
                || lifetime.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException(
                    "credential cursor lifetime must be positive and at most PT24H");
        }
    }

    String encodeCredentials(
            TenantContext tenant,
            UUID principalId,
            CredentialPosition position) {
        return position == null ? null : sign(payload(
                "credential",
                tenant.tenantId().toString(),
                principalId.toString(),
                clock.instant().toString(),
                position.lifecycleState().name(),
                Long.toString(position.createdAt().getEpochSecond()),
                Integer.toString(position.createdAt().getNano()),
                position.id().toString()));
    }

    CredentialPosition decodeCredentials(
            String cursor,
            TenantContext tenant,
            UUID principalId) {
        String[] parts = verifiedPayload(cursor);
        if (parts.length != 9
                || !PAYLOAD_VERSION.equals(parts[0])
                || !"credential".equals(parts[1])
                || !tenant.tenantId().toString().equals(parts[2])
                || !principalId.toString().equals(parts[3])) {
            throw invalid();
        }
        validateIssuedAt(parts[4]);
        try {
            return new CredentialPosition(
                    CredentialState.valueOf(parts[5]),
                    Instant.ofEpochSecond(
                            Long.parseLong(parts[6]),
                            Integer.parseInt(parts[7])),
                    UUID.fromString(parts[8]));
        } catch (RuntimeException invalid) {
            throw invalid(invalid);
        }
    }

    String encodeRotations(
            TenantContext tenant,
            UUID oldCredentialId,
            RotationPosition position) {
        return position == null ? null : sign(payload(
                "credential-rotation",
                tenant.tenantId().toString(),
                oldCredentialId.toString(),
                clock.instant().toString(),
                Long.toString(position.createdAt().getEpochSecond()),
                Integer.toString(position.createdAt().getNano()),
                position.id().toString()));
    }

    RotationPosition decodeRotations(
            String cursor,
            TenantContext tenant,
            UUID oldCredentialId) {
        String[] parts = verifiedPayload(cursor);
        if (parts.length != 8
                || !PAYLOAD_VERSION.equals(parts[0])
                || !"credential-rotation".equals(parts[1])
                || !tenant.tenantId().toString().equals(parts[2])
                || !oldCredentialId.toString().equals(parts[3])) {
            throw invalid();
        }
        validateIssuedAt(parts[4]);
        try {
            return new RotationPosition(
                    Instant.ofEpochSecond(
                            Long.parseLong(parts[5]),
                            Integer.parseInt(parts[6])),
                    UUID.fromString(parts[7]));
        } catch (RuntimeException invalid) {
            throw invalid(invalid);
        }
    }

    private String sign(String payload) {
        SigningKeyProvider keys = requireKeys();
        SigningKeyMaterial key = keys.currentSigningKey();
        if (!key.keyId().matches("[A-Za-z0-9_-]{1,100}")) {
            throw new IllegalStateException(
                    "active signing key ID is not cursor-safe");
        }
        String encodedPayload =
                encode(payload.getBytes(StandardCharsets.UTF_8));
        String signedValue =
                ENVELOPE_VERSION + "." + key.keyId() + "." + encodedPayload;
        return signedValue + "."
                + encode(keys.sign(
                        signedValue.getBytes(StandardCharsets.UTF_8)));
    }

    private String[] verifiedPayload(String cursor) {
        if (cursor == null
                || cursor.isBlank()
                || cursor.length() > MAX_CURSOR_LENGTH) {
            throw invalid();
        }
        String[] envelope = cursor.split("\\.", -1);
        if (envelope.length != 4
                || !ENVELOPE_VERSION.equals(envelope[0])) {
            throw invalid();
        }
        SigningKeyMaterial key = requireKeys()
                .verificationKey(envelope[1])
                .orElseThrow(CredentialCursorCodec::invalid);
        String signedValue =
                envelope[0] + "." + envelope[1] + "." + envelope[2];
        if (!verify(
                key,
                signedValue.getBytes(StandardCharsets.UTF_8),
                decode(envelope[3]))) {
            throw invalid();
        }
        return new String(
                decode(envelope[2]),
                StandardCharsets.UTF_8).split("\u0000", -1);
    }

    private void validateIssuedAt(String value) {
        try {
            Instant issuedAt = Instant.parse(value);
            Instant now = clock.instant();
            if (issuedAt.isAfter(now)
                    || !now.isBefore(issuedAt.plus(lifetime))) {
                throw invalid();
            }
        } catch (RuntimeException invalid) {
            if (invalid instanceof IllegalArgumentException argument) {
                throw argument;
            }
            throw invalid(invalid);
        }
    }

    private static String payload(String... parts) {
        for (String part : parts) {
            if (part == null || part.indexOf('\u0000') >= 0) {
                throw invalid();
            }
        }
        return PAYLOAD_VERSION + "\u0000"
                + String.join("\u0000", parts);
    }

    private static boolean verify(
            SigningKeyMaterial key,
            byte[] payload,
            byte[] signatureBytes) {
        try {
            Signature verifier =
                    Signature.getInstance(key.signingAlgorithm());
            verifier.initVerify(key.publicKey());
            verifier.update(payload);
            return verifier.verify(signatureBytes);
        } catch (GeneralSecurityException invalid) {
            throw invalid(invalid);
        }
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value);
    }

    private static byte[] decode(String value) {
        try {
            byte[] decoded =
                    Base64.getUrlDecoder().decode(value);
            if (!encode(decoded).equals(value)) {
                throw invalid();
            }
            return decoded;
        } catch (IllegalArgumentException invalid) {
            throw invalid(invalid);
        }
    }

    private SigningKeyProvider requireKeys() {
        if (signingKeys == null) {
            throw new IllegalStateException(
                    "cursor signing keys are not configured");
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
