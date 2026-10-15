package io.wyrmgate.iam.installation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstallationSetupSessionCodecTest {

    @TempDir
    Path tempDir;

    @Test
    void validatesProofAndExpiresIssuedSession() throws Exception {
        Path secretFile = tempDir.resolve("setup-secret");
        String fixture = "setup-fixture-".repeat(3);
        Files.writeString(secretFile, fixture + "\n");
        InstallationSetupSessionCodec codec = new InstallationSetupSessionCodec(secretFile, Duration.ofMinutes(15));

        char[] valid = fixture.toCharArray();
        char[] invalid = (fixture + "x").toCharArray();
        Instant issuedAt = Instant.parse("2026-10-15T08:00:00Z");
        try {
            assertTrue(codec.authenticate(valid));
            assertFalse(codec.authenticate(invalid));

            String token = codec.issue(issuedAt);
            assertTrue(codec.verify(token, issuedAt.plusSeconds(899)));
            assertFalse(codec.verify(token, issuedAt.plusSeconds(900)));
            assertFalse(codec.verify(token + "tampered", issuedAt));
        } finally {
            Arrays.fill(valid, '\0');
            Arrays.fill(invalid, '\0');
        }
    }

    @Test
    void csrfRequiresExactCookieHeaderPair() throws Exception {
        Path secretFile = tempDir.resolve("setup-secret");
        Files.writeString(secretFile, "setup-fixture-".repeat(3));
        InstallationSetupSessionCodec codec = new InstallationSetupSessionCodec(secretFile, Duration.ofMinutes(5));

        String csrf = codec.issueCsrf();
        assertTrue(codec.verifyCsrf(csrf, csrf));
        assertFalse(codec.verifyCsrf(csrf, csrf + "x"));
        assertFalse(codec.verifyCsrf(null, csrf));
    }
}
