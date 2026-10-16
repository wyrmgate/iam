package io.wyrmgate.iam.installation;

import io.wyrmgate.iam.administration.application.SealedInstallationBootstrapQuery;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Exposes only semantic sealed-bootstrap evidence to Administration. */
@Configuration
public class InstallationAdoptionEvidenceConfiguration {

    @Bean
    SealedInstallationBootstrapQuery sealedInstallationBootstrapQuery(
            InstallationBootstrapRepository repository) {
        return new InstallationSealedBootstrapQueryAdapter(repository);
    }
}
