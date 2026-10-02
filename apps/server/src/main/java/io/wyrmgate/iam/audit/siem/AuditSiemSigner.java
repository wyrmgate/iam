package io.wyrmgate.iam.audit.siem;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class AuditSiemSigner {

    private final byte[] secret;

    AuditSiemSigner(byte[] secret) {
        if (secret == null || secret.length < 32) {
            throw new IllegalArgumentException("SIEM signing secret must be at least 32 bytes");
        }
        this.secret = secret.clone();
    }

    String sign(long unixSeconds, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update(Long.toString(unixSeconds).getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) '.');
            mac.update(body);
            return "v1=" + HexFormat.of().formatHex(mac.doFinal());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("HmacSHA256 unavailable", impossible);
        } catch (InvalidKeyException invalid) {
            throw new IllegalStateException("SIEM signing key invalid", invalid);
        }
    }
}
