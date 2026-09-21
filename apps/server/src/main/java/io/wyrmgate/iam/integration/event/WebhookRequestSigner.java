package io.wyrmgate.iam.integration.event;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA-256 signer for ADR-0013 webhook deliveries. */
final class WebhookRequestSigner {

    private final byte[] secret;

    WebhookRequestSigner(byte[] secret) {
        Objects.requireNonNull(secret, "secret");
        if (secret.length < 32) {
            throw new IllegalArgumentException("webhook signing secret must be at least 32 bytes");
        }
        this.secret = secret.clone();
    }

    String sign(long unixSeconds, byte[] body) {
        Objects.requireNonNull(body, "body");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update(Long.toString(unixSeconds).getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) '.');
            mac.update(body);
            return "v1=" + HexFormat.of().formatHex(mac.doFinal());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable", exception);
        } catch (InvalidKeyException exception) {
            throw new IllegalStateException("webhook signing key is invalid", exception);
        }
    }
}
