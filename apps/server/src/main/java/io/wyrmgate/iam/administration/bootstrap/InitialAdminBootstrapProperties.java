package io.wyrmgate.iam.administration.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "iam.bootstrap.initial-admin")
public record InitialAdminBootstrapProperties(
        boolean enabled,
        String tenantId,
        String identityId,
        String issuer,
        String subject) {
}
