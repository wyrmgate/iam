package io.wyrmgate.iam.administration.notification;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class BreakGlassNotificationSigner {
    private final byte[] secret;

    BreakGlassNotificationSigner(byte[] secret) {
        if (secret == null || secret.length < 32) {
            throw new IllegalArgumentException("notification signing secret must be at least 32 bytes");
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
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable", exception);
        }
    }
}
