package io.wyrmgate.iam.installation;

import io.wyrmgate.iam.administration.application.InitialAdminBootstrapService;
import io.wyrmgate.iam.catalog.application.CatalogCommandService;
import io.wyrmgate.iam.catalog.application.SsoClientProtocolQuery;
import io.wyrmgate.iam.catalog.application.SsoClientRegistrationService;
import io.wyrmgate.iam.credential.application.CredentialAuthenticationService;
import io.wyrmgate.iam.credential.application.PasswordCredentialEnrollmentService;
import io.wyrmgate.iam.identity.application.IdentityAuthenticationQuery;
import io.wyrmgate.iam.identity.application.IdentityCommandService;
import io.wyrmgate.iam.identity.application.PrincipalCommandService;
import io.wyrmgate.iam.idp.session.ConsoleLoginRouteQuery;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.tenant.TenantProvisioningService;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Trusted local/startup adapter for ADR-0044. No HTTP bootstrap endpoint is exposed. */
@Configuration
@EnableConfigurationProperties(InstallationBootstrapProperties.class)
public class InstallationBootstrapConfiguration {

    private static final int MAX_PASSWORD_CHARS = 4096;

    @Bean
    InstallationBootstrapRepository installationBootstrapRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcInstallationBootstrapRepository(jdbcTemplate);
    }

    @Bean
    ConsoleLoginRouteQuery consoleLoginRouteQuery(
            InstallationBootstrapRepository repository,
            SsoClientProtocolQuery protocolClients) {
        return new InstallationConsoleLoginRouteQuery(repository, protocolClients);
    }

    @Bean
    InstallationBootstrapService installationBootstrapService(
            InstallationBootstrapRepository repository,
            TenantProvisioningService tenants,
            IdentityCommandService identities,
            CatalogCommandService catalog,
            SsoClientRegistrationService ssoClients,
            PrincipalCommandService principals,
            PasswordCredentialEnrollmentService passwords,
            InitialAdminBootstrapService administration,
            IdentityAuthenticationQuery authenticationSubjects,
            CredentialAuthenticationService credentialAuthentication,
            SsoClientProtocolQuery protocolClients,
            IdGenerator ids) {
        return new InstallationBootstrapService(
                repository,
                tenants,
                identities,
                catalog,
                ssoClients,
                principals,
                passwords,
                administration,
                authenticationSubjects,
                credentialAuthentication,
                protocolClients,
                ids);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "iam.bootstrap.installation",
            name = "enabled",
            havingValue = "true")
    ApplicationRunner installationBootstrapRunner(
            InstallationBootstrapProperties properties,
            InstallationBootstrapService service) {
        return args -> {
            InstallationBootstrapService.Input input = new InstallationBootstrapService.Input(
                    properties.tenantDisplayName(),
                    properties.identityDisplayName(),
                    properties.username(),
                    properties.consoleRedirectUri());

            if (service.isSealed()) {
                service.run(input, null, Instant.now());
                return;
            }

            Path passwordPath = Path.of(requireText(properties.passwordFile(), "password-file"));
            char[] password = readPassword(passwordPath);
            try {
                service.run(input, password, Instant.now());
            } finally {
                Arrays.fill(password, '\0');
            }
        };
    }

    private static char[] readPassword(Path path) throws IOException {
        char[] buffer = new char[MAX_PASSWORD_CHARS + 1];
        int count = 0;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            while (count < buffer.length) {
                int read = reader.read(buffer, count, buffer.length - count);
                if (read < 0) break;
                count += read;
            }
            if (count == buffer.length) {
                throw new IllegalStateException("bootstrap password file exceeds maximum length");
            }
            if (count > 0 && buffer[count - 1] == '\n') {
                count--;
                if (count > 0 && buffer[count - 1] == '\r') count--;
            }
            if (count == 0) {
                throw new IllegalStateException("bootstrap password file is empty");
            }
            return Arrays.copyOf(buffer, count);
        } finally {
            Arrays.fill(buffer, '\0');
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("iam.bootstrap.installation." + name + " is required");
        }
        return value.trim();
    }
}
