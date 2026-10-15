package io.wyrmgate.iam.api.governance;

import io.wyrmgate.iam.api.security.ControlPlaneAuthProperties;
import io.wyrmgate.iam.governance.application.GovernanceSubjectQuery.Position;
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
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class GovernanceSubjectCursorCodec {
    private static final String VERSION = "v1";
    private final SigningKeyProvider keys;
    private final Duration lifetime;
    private final Clock clock = Clock.systemUTC();

    GovernanceSubjectCursorCodec(
            ObjectProvider<SigningKeyProvider> signingKeys,
            ControlPlaneAuthProperties authProperties,
            @Value("${iam.api.cursor.lifetime:PT15M}") Duration lifetime) {
        this.keys = signingKeys.getIfAvailable();
        this.lifetime = lifetime;
        if (authProperties.enabled() && this.keys == null) {
            throw new IllegalStateException("signing key material is required when control-plane authentication is enabled");
        }
    }

    String encode(String kind, TenantContext tenant, UUID subjectIdentityId, Position position) {
        if (position == null) return null;
        String payload = String.join("\u0000",
                VERSION, kind, tenant.tenantId().toString(), subjectIdentityId.toString(), clock.instant().toString(),
                Long.toString(position.createdAt().getEpochSecond()), Integer.toString(position.createdAt().getNano()),
                position.id().toString());
        SigningKeyMaterial key = requireKeys().currentSigningKey();
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String signed = VERSION + "." + key.keyId() + "." + encoded;
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(requireKeys().sign(signed.getBytes(StandardCharsets.UTF_8)));
        return signed + "." + signature;
    }

    Position decode(String cursor, String kind, TenantContext tenant, UUID subjectIdentityId) {
        try {
            String[] envelope = cursor.split("\\.", -1);
            if (envelope.length != 4 || !VERSION.equals(envelope[0])) throw invalid();
            SigningKeyMaterial key = requireKeys().verificationKey(envelope[1]).orElseThrow(GovernanceSubjectCursorCodec::invalid);
            String signed = envelope[0] + "." + envelope[1] + "." + envelope[2];
            byte[] sig = Base64.getUrlDecoder().decode(envelope[3]);
            if (!verify(key, signed.getBytes(StandardCharsets.UTF_8), sig)) throw invalid();
            String payload = new String(Base64.getUrlDecoder().decode(envelope[2]), StandardCharsets.UTF_8);
            String[] parts = payload.split("\u0000", -1);
            if (parts.length != 8 || !VERSION.equals(parts[0]) || !kind.equals(parts[1])
                    || !tenant.tenantId().toString().equals(parts[2]) || !subjectIdentityId.toString().equals(parts[3])) throw invalid();
            Instant issuedAt = Instant.parse(parts[4]);
            Instant now = clock.instant();
            if (issuedAt.isAfter(now) || !now.isBefore(issuedAt.plus(lifetime))) throw invalid();
            return new Position(
                    Instant.ofEpochSecond(Long.parseLong(parts[5]), Integer.parseInt(parts[6])),
                    UUID.fromString(parts[7]));
        } catch (RuntimeException invalid) {
            throw invalid();
        }
    }

    private SigningKeyProvider requireKeys() {
        if (keys == null) throw new IllegalStateException("cursor signing keys are not configured");
        return keys;
    }
    private static boolean verify(SigningKeyMaterial key, byte[] payload, byte[] signature) {
        try {
            Signature verifier = Signature.getInstance(key.signingAlgorithm());
            verifier.initVerify(key.publicKey()); verifier.update(payload); return verifier.verify(signature);
        } catch (GeneralSecurityException error) { throw new IllegalArgumentException("invalid cursor", error); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid cursor"); }
}
