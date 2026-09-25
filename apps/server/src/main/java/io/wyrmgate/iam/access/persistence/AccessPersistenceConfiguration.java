package io.wyrmgate.iam.access.persistence;

import io.wyrmgate.iam.access.application.AccessAssignmentBoundaryScheduler;
import io.wyrmgate.iam.access.application.AccessAssignmentCommandService;
import io.wyrmgate.iam.access.application.AccessAssignmentFactSink;
import io.wyrmgate.iam.access.application.AccessAssignmentRepository;
import io.wyrmgate.iam.access.application.AccessDesiredStateQueryService;
import io.wyrmgate.iam.access.application.EffectiveAccessProcessingService;
import io.wyrmgate.iam.access.application.EffectiveAccessQuery;
import io.wyrmgate.iam.access.application.EffectiveAccessQueryService;
import io.wyrmgate.iam.access.application.EffectiveAccessRepository;
import io.wyrmgate.iam.access.application.DesiredAccessStateQuery;
import io.wyrmgate.iam.access.application.DesiredStateDerivationService;
import io.wyrmgate.iam.access.application.DesiredStateProcessingService;
import io.wyrmgate.iam.access.application.DesiredStateProjectionRepository;
import io.wyrmgate.iam.catalog.application.CatalogAccessReferenceQuery;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQuery;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class AccessPersistenceConfiguration {

    @Bean
    AccessAssignmentRepository accessAssignmentRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcAccessAssignmentRepository(jdbcTemplate);
    }

    @Bean
    AccessAssignmentCommandService accessAssignmentCommandService(
            AccessAssignmentRepository repository,
            IdentityAccessReferenceQuery identityReferences,
            CatalogAccessReferenceQuery catalogReferences,
            AccessAssignmentFactSink facts,
            AccessAssignmentBoundaryScheduler boundaries,
            IdGenerator idGenerator,
            TransactionExecutor transactionExecutor) {
        return new AccessAssignmentCommandService(
                repository,
                identityReferences,
                catalogReferences,
                facts,
                boundaries,
                idGenerator,
                transactionExecutor);
    }

    @Bean
    AccessAssignmentFactSink accessAssignmentFactSink(
            JdbcOutboxRepository outboxRepository,
            IdGenerator idGenerator) {
        return new JdbcAccessAssignmentFactSink(outboxRepository, idGenerator);
    }

    @Bean
    AccessAssignmentBoundaryScheduler accessAssignmentBoundaryScheduler(
            JdbcScheduledWorkRepository scheduledWorkRepository) {
        return new JdbcAccessAssignmentBoundaryScheduler(scheduledWorkRepository);
    }

    @Bean
    EffectiveAccessRepository effectiveAccessRepository(
            JdbcTemplate jdbcTemplate,
            IdGenerator idGenerator) {
        return new JdbcEffectiveAccessRepository(jdbcTemplate, idGenerator);
    }

    @Bean
    EffectiveAccessQuery effectiveAccessQuery(EffectiveAccessRepository repository) {
        return new EffectiveAccessQueryService(repository);
    }

    @Bean
    EffectiveAccessProcessingService effectiveAccessProcessingService(
            JdbcOutboxRepository outboxRepository,
            JdbcScheduledWorkRepository scheduledWorkRepository,
            AccessAssignmentRepository assignmentRepository,
            EffectiveAccessRepository effectiveAccessRepository,
            DesiredStateDerivationService desiredStateDerivationService) {
        return new EffectiveAccessProcessingService(
                outboxRepository,
                scheduledWorkRepository,
                assignmentRepository,
                effectiveAccessRepository,
                desiredStateDerivationService);
    }

    @Bean
    EffectiveAccessProcessingScheduler effectiveAccessProcessingScheduler(
            EffectiveAccessProcessingService service) {
        return new EffectiveAccessProcessingScheduler(service);
    }

    @Bean
    DesiredStateProjectionRepository desiredStateProjectionRepository(
            JdbcTemplate jdbcTemplate,
            IdGenerator idGenerator) {
        return new JdbcDesiredStateProjectionRepository(jdbcTemplate, idGenerator);
    }

    @Bean
    DesiredStateDerivationService desiredStateDerivationService(
            EffectiveAccessQuery effectiveAccessQuery,
            DesiredStateProjectionRepository desiredStateRepository,
            CatalogAccessReferenceQuery catalogReferences,
            IdentityAccessReferenceQuery identityReferences) {
        return new DesiredStateDerivationService(
                effectiveAccessQuery,
                desiredStateRepository,
                catalogReferences,
                identityReferences);
    }

    @Bean
    DesiredStateProcessingService desiredStateProcessingService(
            JdbcOutboxRepository outboxRepository,
            IdentityAccessReferenceQuery identityReferences,
            DesiredStateDerivationService derivationService) {
        return new DesiredStateProcessingService(
                outboxRepository, identityReferences, derivationService);
    }

    @Bean
    DesiredStateProcessingScheduler desiredStateProcessingScheduler(
            DesiredStateProcessingService service) {
        return new DesiredStateProcessingScheduler(service);
    }

    @Bean
    DesiredAccessStateQuery desiredAccessStateQuery(DesiredStateProjectionRepository repository) {
        return new AccessDesiredStateQueryService(repository);
    }
}
