package io.wyrmgate.iam.installation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstallationWebSetupConfigurationTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsCompetingStartupAndWebMutationAdapters() throws Exception {
        Path secret = setupSecret();
        InstallationWebSetupProperties web = new InstallationWebSetupProperties(true, secret.toString(), Duration.ofMinutes(15));
        InstallationBootstrapProperties bootstrap = bootstrap(true, "https://wyrmgate.example/callback");

        assertThrows(
                IllegalStateException.class,
                () -> new InstallationWebSetupConfiguration().installationSetupSessionCodec(web, bootstrap));
    }

    @Test
    void requiresTrustedConsoleRedirect() throws Exception {
        Path secret = setupSecret();
        InstallationWebSetupProperties web = new InstallationWebSetupProperties(true, secret.toString(), Duration.ofMinutes(15));

        assertThrows(
                IllegalStateException.class,
                () -> new InstallationWebSetupConfiguration().installationSetupSessionCodec(web, bootstrap(false, null)));
    }

    @Test
    void acceptsFileOnlyProofWithBoundedSessionTtl() throws Exception {
        Path secret = setupSecret();
        InstallationWebSetupProperties web = new InstallationWebSetupProperties(true, secret.toString(), Duration.ofMinutes(15));

        assertDoesNotThrow(
                () -> new InstallationWebSetupConfiguration().installationSetupSessionCodec(
                        web, bootstrap(false, "https://wyrmgate.example/callback")));
        assertThrows(
                IllegalStateException.class,
                () -> new InstallationWebSetupProperties(true, secret.toString(), Duration.ofHours(2)).effectiveSessionTtl());
    }

    private Path setupSecret() throws Exception {
        Path secret = tempDir.resolve("setup-secret");
        Files.writeString(secret, "setup-fixture-".repeat(3) + "\n");
        return secret;
    }

    private static InstallationBootstrapProperties bootstrap(boolean enabled, String redirect) {
        return new InstallationBootstrapProperties(
                enabled,
                null,
                null,
                null,
                null,
                null,
                redirect);
    }
}
