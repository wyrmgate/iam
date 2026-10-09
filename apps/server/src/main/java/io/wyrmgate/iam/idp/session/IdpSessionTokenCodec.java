package io.wyrmgate.iam.idp.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

/** Generates high-entropy opaque session tokens and stores only SHA-256 digests. */
public final class IdpSessionTokenCodec {

    private static final int TOKEN_BYTES = 32;

    private final SecureRandom random;

    public IdpSessionTokenCodec() {
        this(new SecureRandom());
    }

    IdpSessionTokenCodec(SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    public IssuedToken issue() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new IssuedToken(value, hash(value));
    }

    public String hash(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("session token must not be blank");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    public static final class IssuedToken {
        private final String value;
        private final String hash;

        IssuedToken(String value, String hash) {
            this.value = value;
            this.hash = hash;
        }

        public String value() {
            return value;
        }

        public String hash() {
            return hash;
        }

        @Override
        public String toString() {
            return "IssuedToken[REDACTED]";
        }
    }
}
