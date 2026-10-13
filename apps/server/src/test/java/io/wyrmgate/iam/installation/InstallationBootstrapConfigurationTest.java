package io.wyrmgate.iam.installation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstallationBootstrapConfigurationTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesTemporarySecretWithoutTransformingPassword() throws Exception {
        InstallationBootstrapProperties properties = properties("  exact secret  ", null);

        char[] resolved = InstallationBootstrapConfiguration.resolvePassword(properties);

        assertArrayEquals("  exact secret  ".toCharArray(), resolved);
    }

    @Test
    void preservesExistingFileBackedPasswordOption() throws Exception {
        Path passwordFile = tempDir.resolve("initial-password");
        Files.writeString(passwordFile, "from-file\n");
        InstallationBootstrapProperties properties = properties(null, passwordFile.toString());

        char[] resolved = InstallationBootstrapConfiguration.resolvePassword(properties);

        assertArrayEquals("from-file".toCharArray(), resolved);
    }

    @Test
    void rejectsBothSecretSources() {
        InstallationBootstrapProperties properties = properties("secret", "/run/secrets/initial-password");

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> InstallationBootstrapConfiguration.resolvePassword(properties));

        assertTrue(failure.getMessage().contains("exactly one"));
    }

    @Test
    void rejectsMissingSecretSourceBeforeSeal() {
        InstallationBootstrapProperties properties = properties(null, null);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> InstallationBootstrapConfiguration.resolvePassword(properties));

        assertTrue(failure.getMessage().contains("exactly one"));
    }

    private static InstallationBootstrapProperties properties(String password, String passwordFile) {
        return new InstallationBootstrapProperties(
                true,
                "Wyrmgate",
                "Initial Administrator",
                "admin",
                password,
                passwordFile,
                "https://wyrmgate.example/callback");
    }
}
