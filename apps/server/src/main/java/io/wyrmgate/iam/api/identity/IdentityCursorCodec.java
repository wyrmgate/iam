package io.wyrmgate.iam.api.identity;

import io.wyrmgate.iam.identity.application.IdentityQueryModels.CanonicalAttributePagePosition;
import io.wyrmgate.iam.identity.application.IdentityQueryModels.IdentityPagePosition;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

final class IdentityCursorCodec {

    private static final String VERSION = "v1";
    private static final int MAX_CURSOR_LENGTH = 4096;

    private IdentityCursorCodec() {
    }

    static String encodeIdentity(IdentityPagePosition position) {
        if (position == null) return null;
        return encode(VERSION + "\u0000" + position.createdAt().getEpochSecond() + "\u0000"
                + position.createdAt().getNano() + "\u0000" + position.id());
    }

    static IdentityPagePosition decodeIdentity(String cursor) {
        String[] parts = decode(cursor).split("\u0000", -1);
        if (parts.length != 4 || !VERSION.equals(parts[0])) {
            throw new IllegalArgumentException("invalid Identity cursor");
        }
        try {
            return new IdentityPagePosition(
                    Instant.ofEpochSecond(Long.parseLong(parts[1]), Integer.parseInt(parts[2])),
                    UUID.fromString(parts[3]));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("invalid Identity cursor", invalid);
        }
    }

    static String encodeCanonical(CanonicalAttributePagePosition position) {
        if (position == null) return null;
        return encode(VERSION + "\u0000" + position.key() + "\u0000" + position.definitionId());
    }

    static CanonicalAttributePagePosition decodeCanonical(String cursor) {
        String[] parts = decode(cursor).split("\u0000", -1);
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            throw new IllegalArgumentException("invalid canonical-attribute cursor");
        }
        try {
            return new CanonicalAttributePagePosition(parts[1], UUID.fromString(parts[2]));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("invalid canonical-attribute cursor", invalid);
        }
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String cursor) {
        if (cursor == null || cursor.isBlank() || cursor.length() > MAX_CURSOR_LENGTH) {
            throw new IllegalArgumentException("invalid cursor");
        }
        try {
            return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("invalid cursor", invalid);
        }
    }
}
