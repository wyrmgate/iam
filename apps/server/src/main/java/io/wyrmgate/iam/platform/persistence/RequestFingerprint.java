package io.wyrmgate.iam.platform.persistence;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Fingerprint of caller-normalized request bytes for causal idempotency. */
public record RequestFingerprint(String value) {

    public RequestFingerprint {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("fingerprint must not be blank");
        }
    }

    public static RequestFingerprint sha256(byte[] canonicalRequestBytes) {
        if (canonicalRequestBytes == null) {
            throw new NullPointerException("canonicalRequestBytes");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalRequestBytes);
            return new RequestFingerprint("sha256:" + HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
