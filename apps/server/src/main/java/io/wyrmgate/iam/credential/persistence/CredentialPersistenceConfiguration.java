package io.wyrmgate.iam.credential.persistence;

import io.wyrmgate.iam.credential.application.CredentialAuthenticationService;
import io.wyrmgate.iam.credential.application.CredentialBoundaryProcessingScheduler;
import io.wyrmgate.iam.credential.application.CredentialBoundaryProcessingService;
import io.wyrmgate.iam.credential.application.CredentialBoundaryScheduler;
import io.wyrmgate.iam.credential.application.CredentialQueryService;
import io.wyrmgate.iam.credential.application.CredentialRepository;
import io.wyrmgate.iam.credential.application.CredentialRotationService;
import io.wyrmgate.iam.credential.application.CredentialSecretVerifier;
import io.wyrmgate.iam.credential.application.CredentialService;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQuery;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class CredentialPersistenceConfiguration {

    @Bean
    CredentialRepository credentialRepository(
            JdbcTemplate jdbc) {
        return new JdbcCredentialRepository(jdbc);
    }

    @Bean
    CredentialQueryService credentialQueryService(
            CredentialRepository credentials) {
        return new CredentialQueryService(credentials);
    }

    @Bean
    CredentialAuthenticationService credentialAuthenticationService(
            CredentialRepository credentials,
            List<CredentialSecretVerifier> verifiers) {
        return new CredentialAuthenticationService(credentials, verifiers);
    }

    @Bean
    CredentialBoundaryScheduler credentialBoundaryScheduler(
            JdbcScheduledWorkRepository scheduledWork) {
        return new JdbcCredentialBoundaryScheduler(
                scheduledWork);
    }

    @Bean
    CredentialService credentialService(
            CredentialRepository credentials,
            IdentityAccessReferenceQuery principals,
            CredentialBoundaryScheduler boundaries,
            IdGenerator ids,
            TransactionExecutor transactions) {
        return new CredentialService(
                credentials,
                principals,
                boundaries,
                ids,
                transactions);
    }

    @Bean
    CredentialRotationService credentialRotationService(
            CredentialRepository credentials,
            IdentityAccessReferenceQuery identities,
            IdGenerator ids,
            TransactionExecutor transactions) {
        return new CredentialRotationService(
                credentials,
                identities,
                ids,
                transactions);
    }

    @Bean
    CredentialBoundaryProcessingService credentialBoundaryProcessingService(
            JdbcScheduledWorkRepository scheduledWork,
            CredentialService credentials) {
        return new CredentialBoundaryProcessingService(
                scheduledWork, credentials);
    }

    @Bean
    CredentialBoundaryProcessingScheduler credentialBoundaryProcessingScheduler(
            CredentialBoundaryProcessingService service) {
        return new CredentialBoundaryProcessingScheduler(
                service);
    }
}
