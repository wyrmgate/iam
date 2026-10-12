package io.wyrmgate.iam.installation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Non-secret fresh-install inputs plus a local password file path. */
@ConfigurationProperties(prefix = "iam.bootstrap.installation")
public record InstallationBootstrapProperties(
        boolean enabled,
        String tenantDisplayName,
        String identityDisplayName,
        String username,
        String passwordFile,
        String consoleRedirectUri) {
}
