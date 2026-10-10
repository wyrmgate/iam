package io.wyrmgate.iam.idp.session;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** High-entropy double-submit CSRF token for same-origin cookie-authenticated browser requests. */
public final class IdpCsrfTokenCodec {

    private static final int TOKEN_BYTES = 32;
    private final SecureRandom random = new SecureRandom();

    public String issue() {
        byte[] value = new byte[TOKEN_BYTES];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public boolean matches(String cookieValue, String headerValue) {
        if (cookieValue == null || headerValue == null
                || cookieValue.isBlank() || headerValue.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                cookieValue.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                headerValue.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
}
