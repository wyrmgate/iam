package io.wyrmgate.iam.installation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Fresh-install inputs plus mutually exclusive trusted password sources. */
@ConfigurationProperties(prefix = "iam.bootstrap.installation")
public record InstallationBootstrapProperties(
        boolean enabled,
        String tenantDisplayName,
        String identityDisplayName,
        String username,
        String password,
        String passwordFile,
        String consoleRedirectUri) {
}
