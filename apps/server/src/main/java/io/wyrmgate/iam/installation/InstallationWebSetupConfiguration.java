package io.wyrmgate.iam.installation;

import java.io.IOException;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/** Secure browser adapter for the durable ADR-0044 installation bootstrap engine. */
@Configuration
@ConditionalOnProperty(prefix = "iam.bootstrap.web", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(InstallationWebSetupProperties.class)
public class InstallationWebSetupConfiguration {

    @Bean
    InstallationSetupSessionCodec installationSetupSessionCodec(
            InstallationWebSetupProperties web,
            InstallationBootstrapProperties bootstrap) throws IOException {
        if (bootstrap.enabled()) {
            throw new IllegalStateException(
                    "iam.bootstrap.web.enabled and iam.bootstrap.installation.enabled are mutually exclusive");
        }
        if (web.setupSecretFile() == null || web.setupSecretFile().isBlank()) {
            throw new IllegalStateException("iam.bootstrap.web.setup-secret-file is required when web setup is enabled");
        }
        if (bootstrap.consoleRedirectUri() == null || bootstrap.consoleRedirectUri().isBlank()) {
            throw new IllegalStateException(
                    "iam.bootstrap.installation.console-redirect-uri is required when web setup is enabled");
        }
        return new InstallationSetupSessionCodec(
                Path.of(web.setupSecretFile().trim()), web.effectiveSessionTtl());
    }

    /**
     * Framework routing only makes the setup surface reachable. Authorization remains setup-
     * specific and is enforced by InstallationWebSetupController using the install-scoped proof.
     */
    @Bean
    @Order(0)
    SecurityFilterChain installationWebSetupSecurity(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/setup/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }
}
