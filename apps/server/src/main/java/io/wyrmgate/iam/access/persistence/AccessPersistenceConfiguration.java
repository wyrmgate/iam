package io.wyrmgate.iam.access.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.access.application.AccessAssignmentBoundaryScheduler;
import io.wyrmgate.iam.access.application.AccessAssignmentCommandService;
import io.wyrmgate.iam.access.application.AccessAssignmentFactSink;
import io.wyrmgate.iam.access.application.AccessAssignmentRepository;
import io.wyrmgate.iam.access.application.AccessAssignmentQueryService;
import io.wyrmgate.iam.access.application.AccessIntentCommand;
import io.wyrmgate.iam.access.application.AccessIntentCommandService;
import io.wyrmgate.iam.access.application.AccessReviewSnapshotQuery;
import io.wyrmgate.iam.access.application.AccessReviewSnapshotQueryService;
import io.wyrmgate.iam.access.application.AccessReviewRemediationCommand;
import io.wyrmgate.iam.access.application.AccessReviewRemediationCommandService;
import io.wyrmgate.iam.access.application.AccessReviewRemediationRepository;
import io.wyrmgate.iam.access.application.AccessDesiredStateQueryService;
import io.wyrmgate.iam.access.application.EffectiveAccessProcessingService;
import io.wyrmgate.iam.access.application.EffectiveAccessReadService;
import io.wyrmgate.iam.access.application.EffectiveAccessQuery;
import io.wyrmgate.iam.access.application.EffectiveAccessQueryService;
import io.wyrmgate.iam.access.application.EffectiveAccessRepository;
import io.wyrmgate.iam.access.application.IdentityAccessReductionIntakeService;
import io.wyrmgate.iam.access.application.IdentityAccessReductionProcessingService;
import io.wyrmgate.iam.access.application.IdentityAccessReductionRepository;
import io.wyrmgate.iam.access.application.IdentityAccessReductionWorkSink;
import io.wyrmgate.iam.access.application.LifecycleAccessPolicyRepository;
import io.wyrmgate.iam.access.application.LifecycleAccessPolicyService;
import io.wyrmgate.iam.access.application.LifecycleAccessPrivilegeGuard;
import io.wyrmgate.iam.access.application.LifecycleAccessApprovalCommand;
import io.wyrmgate.iam.access.application.LifecycleAccessReconciliationService;
import io.wyrmgate.iam.access.application.DesiredAccessStateQuery;
import io.wyrmgate.iam.access.application.DesiredGrantFactSink;
import io.wyrmgate.iam.access.application.DesiredPrincipalFactSink;
import io.wyrmgate.iam.access.application.DesiredProvisioningStateQuery;
import io.wyrmgate.iam.access.application.DesiredProvisioningStateQueryService;
import io.wyrmgate.iam.access.application.DesiredStateDerivationService;
import io.wyrmgate.iam.access.application.DesiredStateProcessingService;
import io.wyrmgate.iam.access.application.DesiredStateProjectionRepository;
import io.wyrmgate.iam.catalog.application.CatalogAccessReferenceQuery;
import io.wyrmgate.iam.catalog.application.RoleExpansionQuery;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQuery;
import io.wyrmgate.iam.identity.application.IdentityLifecycleAccessQuery;
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
    AccessAssignmentQueryService accessAssignmentQueryService(
            AccessAssignmentRepository repository) {
        return new AccessAssignmentQueryService(repository);
    }

    @Bean
    AccessAssignmentCommandService accessAssignmentCommandService(
            AccessAssignmentRepository repository,
            IdentityAccessReferenceQuery identityReferences,
            CatalogAccessReferenceQuery catalogReferences,
            RoleExpansionQuery roleExpansion,
            AccessAssignmentFactSink facts,
            AccessAssignmentBoundaryScheduler boundaries,
            IdGenerator idGenerator,
            TransactionExecutor transactionExecutor) {
        return new AccessAssignmentCommandService(
                repository,
                identityReferences,
                catalogReferences,
                roleExpansion,
                facts,
                boundaries,
                idGenerator,
                transactionExecutor);
    }

    @Bean
    LifecycleAccessPolicyRepository lifecycleAccessPolicyRepository(
            JdbcTemplate jdbcTemplate) {
        return new JdbcLifecycleAccessPolicyRepository(jdbcTemplate);
    }

    @Bean
    LifecycleAccessPolicyService lifecycleAccessPolicyService(
            LifecycleAccessPolicyRepository policies,
            IdentityLifecycleAccessQuery identities,
            CatalogAccessReferenceQuery catalog,
            RoleExpansionQuery roles,
            IdGenerator ids,
            TransactionExecutor transactions) {
        return new LifecycleAccessPolicyService(
                policies, identities, catalog, roles, ids, transactions);
    }

    @Bean
    LifecycleAccessReconciliationService lifecycleAccessReconciliationService(
            JdbcOutboxRepository outbox,
            LifecycleAccessPolicyRepository policies,
            AccessAssignmentRepository assignments,
            AccessAssignmentCommandService commands,
            IdentityLifecycleAccessQuery identities,
            LifecycleAccessPrivilegeGuard guard,
            LifecycleAccessApprovalCommand approvals) {
        return new LifecycleAccessReconciliationService(
                outbox, policies, assignments, commands, identities, guard, approvals);
    }

    @Bean
    LifecycleAccessReconciliationScheduler lifecycleAccessReconciliationScheduler(
            LifecycleAccessReconciliationService service) {
        return new LifecycleAccessReconciliationScheduler(service);
    }

    @Bean
    AccessReviewSnapshotQuery accessReviewSnapshotQuery(
            AccessAssignmentRepository repository) {
        return new AccessReviewSnapshotQueryService(repository);
    }

    @Bean
    AccessReviewRemediationRepository accessReviewRemediationRepository(
            JdbcTemplate jdbcTemplate) {
        return new JdbcAccessReviewRemediationRepository(jdbcTemplate);
    }

    @Bean
    AccessReviewRemediationCommand accessReviewRemediationCommand(
            AccessAssignmentRepository assignments,
            AccessAssignmentCommandService commands,
            AccessReviewRemediationRepository applications,
            TransactionExecutor transactions) {
        return new AccessReviewRemediationCommandService(
                assignments,
                commands,
                applications,
                transactions);
    }

    @Bean
    AccessIntentCommand accessIntentCommand(
            AccessAssignmentRepository repository,
            AccessAssignmentCommandService commands) {
        return new AccessIntentCommandService(repository, commands);
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
    IdentityAccessReductionRepository identityAccessReductionRepository(
            JdbcTemplate jdbcTemplate) {
        return new JdbcIdentityAccessReductionRepository(jdbcTemplate);
    }

    @Bean
    IdentityAccessReductionWorkSink identityAccessReductionWorkSink(
            JdbcOutboxRepository outboxRepository,
            IdGenerator idGenerator) {
        return new JdbcIdentityAccessReductionWorkSink(
                outboxRepository, idGenerator);
    }

    @Bean
    IdentityAccessReductionIntakeService identityAccessReductionIntakeService(
            JdbcOutboxRepository outboxRepository,
            IdentityAccessReductionRepository reductions,
            IdentityAccessReductionWorkSink work,
            IdGenerator idGenerator,
            TransactionExecutor transactionExecutor,
            ObjectMapper objectMapper) {
        return new IdentityAccessReductionIntakeService(
                outboxRepository,
                reductions,
                work,
                idGenerator,
                transactionExecutor,
                objectMapper);
    }

    @Bean
    IdentityAccessReductionIntakeScheduler identityAccessReductionIntakeScheduler(
            IdentityAccessReductionIntakeService service) {
        return new IdentityAccessReductionIntakeScheduler(service);
    }

    @Bean
    IdentityAccessReductionProcessingService identityAccessReductionProcessingService(
            JdbcOutboxRepository outboxRepository,
            IdentityAccessReductionRepository reductions,
            AccessAssignmentRepository assignments,
            AccessAssignmentCommandService commands,
            IdentityAccessReferenceQuery identities,
            IdentityAccessReductionWorkSink work,
            TransactionExecutor transactionExecutor) {
        return new IdentityAccessReductionProcessingService(
                outboxRepository,
                reductions,
                assignments,
                commands,
                identities,
                work,
                transactionExecutor);
    }

    @Bean
    IdentityAccessReductionProcessingScheduler identityAccessReductionProcessingScheduler(
            IdentityAccessReductionProcessingService service) {
        return new IdentityAccessReductionProcessingScheduler(service);
    }

    @Bean
    EffectiveAccessRepository effectiveAccessRepository(
            JdbcTemplate jdbcTemplate,
            IdGenerator idGenerator) {
        return new JdbcEffectiveAccessRepository(jdbcTemplate, idGenerator);
    }

    @Bean
    EffectiveAccessQuery effectiveAccessQuery(
            EffectiveAccessRepository repository,
            IdentityAccessReferenceQuery identities) {
        return new EffectiveAccessQueryService(
                repository, identities);
    }

    @Bean
    EffectiveAccessReadService effectiveAccessReadService(
            EffectiveAccessRepository repository,
            IdentityAccessReferenceQuery identities) {
        return new EffectiveAccessReadService(
                repository, identities);
    }

    @Bean
    EffectiveAccessProcessingService effectiveAccessProcessingService(
            JdbcOutboxRepository outboxRepository,
            JdbcScheduledWorkRepository scheduledWorkRepository,
            AccessAssignmentRepository assignmentRepository,
            EffectiveAccessRepository effectiveAccessRepository,
            RoleExpansionQuery roleExpansion,
            DesiredStateDerivationService desiredStateDerivationService) {
        return new EffectiveAccessProcessingService(
                outboxRepository,
                scheduledWorkRepository,
                assignmentRepository,
                effectiveAccessRepository,
                roleExpansion,
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
    DesiredPrincipalFactSink desiredPrincipalFactSink(
            JdbcOutboxRepository outboxRepository,
            IdGenerator idGenerator) {
        return new JdbcDesiredPrincipalFactSink(outboxRepository, idGenerator);
    }

    @Bean
    DesiredGrantFactSink desiredGrantFactSink(
            JdbcOutboxRepository outboxRepository,
            IdGenerator idGenerator) {
        return new JdbcDesiredGrantFactSink(outboxRepository, idGenerator);
    }

    @Bean
    DesiredProvisioningStateQuery desiredProvisioningStateQuery(
            DesiredStateProjectionRepository repository) {
        return new DesiredProvisioningStateQueryService(repository);
    }

    @Bean
    DesiredStateDerivationService desiredStateDerivationService(
            EffectiveAccessQuery effectiveAccessQuery,
            DesiredStateProjectionRepository desiredStateRepository,
            CatalogAccessReferenceQuery catalogReferences,
            IdentityAccessReferenceQuery identityReferences,
            DesiredGrantFactSink desiredGrantFactSink,
            DesiredPrincipalFactSink desiredPrincipalFactSink,
            TransactionExecutor transactionExecutor) {
        return new DesiredStateDerivationService(
                effectiveAccessQuery,
                desiredStateRepository,
                catalogReferences,
                identityReferences,
                desiredGrantFactSink,
                desiredPrincipalFactSink,
                transactionExecutor);
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
