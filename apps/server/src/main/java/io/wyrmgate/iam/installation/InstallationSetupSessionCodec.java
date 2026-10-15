package io.wyrmgate.iam.installation;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Stateless short-lived setup-session tokens derived from a deployment-provided setup secret.
 * The source secret is never persisted, logged, returned, placed in the issued browser token, or
 * retained in plaintext after startup configuration is resolved.
 */
final class InstallationSetupSessionCodec {

    private static final int MAX_SECRET_CHARS = 4096;
    private static final int MIN_SECRET_CHARS = 32;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final byte[] proofDigest;
    private final byte[] signingKey;
    private final Duration ttl;

    InstallationSetupSessionCodec(Path secretFile, Duration ttl) throws IOException {
        char[] secret = readSecret(secretFile);
        try {
            this.proofDigest = derive("wyrmgate-setup-proof", secret);
            this.signingKey = derive("wyrmgate-setup-session", secret);
            this.ttl = ttl;
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    boolean authenticate(char[] candidate) {
        if (candidate == null) return false;
        byte[] candidateDigest = derive("wyrmgate-setup-proof", candidate);
        try {
            return MessageDigest.isEqual(proofDigest, candidateDigest);
        } finally {
            Arrays.fill(candidateDigest, (byte) 0);
        }
    }

    String issue(Instant now) {
        long expiresAt = now.plus(ttl).getEpochSecond();
        byte[] nonce = new byte[24];
        RANDOM.nextBytes(nonce);
        String payload = expiresAt + "." + B64.encodeToString(nonce);
        return payload + "." + B64.encodeToString(mac(payload));
    }

    boolean verify(String token, Instant now) {
        if (token == null || token.isBlank()) return false;
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) return false;
        try {
            long expiresAt = Long.parseLong(parts[0]);
            if (!now.isBefore(Instant.ofEpochSecond(expiresAt))) return false;
            String payload = parts[0] + "." + parts[1];
            byte[] supplied = B64D.decode(parts[2]);
            byte[] expected = mac(payload);
            return MessageDigest.isEqual(expected, supplied);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    String issueCsrf() {
        byte[] token = new byte[24];
        RANDOM.nextBytes(token);
        return B64.encodeToString(token);
    }

    boolean verifyCsrf(String cookie, String header) {
        if (cookie == null || header == null) return false;
        return MessageDigest.isEqual(
                cookie.getBytes(StandardCharsets.US_ASCII),
                header.getBytes(StandardCharsets.US_ASCII));
    }

    private byte[] mac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable", exception);
        }
    }

    private static byte[] derive(String context, char[] secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(context.getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            for (char value : secret) {
                digest.update((byte) (value >>> 8));
                digest.update((byte) value);
            }
            return digest.digest();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static char[] readSecret(Path path) throws IOException {
        char[] buffer = new char[MAX_SECRET_CHARS + 1];
        int count = 0;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            while (count < buffer.length) {
                int read = reader.read(buffer, count, buffer.length - count);
                if (read < 0) break;
                count += read;
            }
            if (count == buffer.length) {
                throw new IllegalStateException("web setup secret file exceeds maximum length");
            }
            if (count > 0 && buffer[count - 1] == '\n') {
                count--;
                if (count > 0 && buffer[count - 1] == '\r') count--;
            }
            if (count < MIN_SECRET_CHARS) {
                throw new IllegalStateException("web setup secret must contain at least 32 characters");
            }
            return Arrays.copyOf(buffer, count);
        } finally {
            Arrays.fill(buffer, '\0');
        }
    }
}
