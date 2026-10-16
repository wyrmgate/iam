package io.wyrmgate.iam.administration.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "iam.bootstrap.platform-owner-adoption")
public record PlatformOwnerAdoptionProperties(
        boolean enabled,
        String tenantId,
        String identityId,
        String principalId,
        String bootstrapCorrelationId) {
}
