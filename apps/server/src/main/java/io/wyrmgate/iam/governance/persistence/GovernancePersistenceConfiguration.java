package io.wyrmgate.iam.governance.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.access.application.AccessIntentCommand;
import io.wyrmgate.iam.catalog.application.CatalogAccessReferenceQuery;
import io.wyrmgate.iam.catalog.application.RoleExpansionQuery;
import io.wyrmgate.iam.governance.application.AccessRequestApprovalProcessingScheduler;
import io.wyrmgate.iam.governance.application.AccessRequestApprovalProcessingService;
import io.wyrmgate.iam.governance.application.AccessRequestApprovalRequirementsResolver;
import io.wyrmgate.iam.governance.application.AccessRequestEligibilityEvaluator;
import io.wyrmgate.iam.governance.application.AccessRequestRepository;
import io.wyrmgate.iam.governance.application.AccessRequestService;
import io.wyrmgate.iam.governance.application.ApprovalActorEligibilityQuery;
import io.wyrmgate.iam.governance.application.ApprovalOutcomeSink;
import io.wyrmgate.iam.governance.application.ApprovalRepository;
import io.wyrmgate.iam.governance.application.ApprovalService;
import io.wyrmgate.iam.governance.application.FailClosedApprovalRequirementsResolver;
import io.wyrmgate.iam.governance.application.StructuralAccessRequestEligibilityEvaluator;
import io.wyrmgate.iam.governance.application.GovernanceFindingRepository;
import io.wyrmgate.iam.governance.application.GovernanceObservationProcessingScheduler;
import io.wyrmgate.iam.governance.application.GovernanceObservationProcessingService;
import io.wyrmgate.iam.governance.application.GovernanceObservationReporter;
import io.wyrmgate.iam.governance.application.GovernanceObservationReportingService;
import io.wyrmgate.iam.governance.application.ObservedAccessDriftEvaluationService;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQuery;
import io.wyrmgate.iam.identity.application.PrincipalResolutionQuery;
import io.wyrmgate.iam.integration.application.IntegrationObservedAccessQuery;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class GovernancePersistenceConfiguration {

    @Bean
    ApprovalRepository approvalRepository(JdbcTemplate jdbc) {
        return new JdbcApprovalRepository(jdbc);
    }

    @Bean
    ApprovalOutcomeSink approvalOutcomeSink(
            JdbcOutboxRepository outbox,
            IdGenerator ids) {
        return new JdbcApprovalOutcomeSink(outbox, ids);
    }

    @Bean
    ApprovalService approvalService(
            ApprovalRepository repository,
            ApprovalOutcomeSink outcomes,
            ApprovalActorEligibilityQuery actorEligibility,
            IdGenerator ids,
            TransactionExecutor transactions) {
        return new ApprovalService(
                repository,
                outcomes,
                actorEligibility,
                ids,
                transactions);
    }

    @Bean
    AccessRequestRepository accessRequestRepository(
            JdbcTemplate jdbc) {
        return new JdbcAccessRequestRepository(jdbc);
    }

    @Bean
    AccessRequestEligibilityEvaluator accessRequestEligibilityEvaluator(
            IdentityAccessReferenceQuery identities,
            CatalogAccessReferenceQuery catalog,
            RoleExpansionQuery roles) {
        return new StructuralAccessRequestEligibilityEvaluator(
                identities, catalog, roles);
    }

    @Bean
    AccessRequestApprovalRequirementsResolver
            accessRequestApprovalRequirementsResolver() {
        return new FailClosedApprovalRequirementsResolver();
    }

    @Bean
    AccessRequestService accessRequestService(
            AccessRequestRepository requests,
            ApprovalRepository approvalRepository,
            ApprovalService approvals,
            AccessRequestEligibilityEvaluator eligibility,
            AccessRequestApprovalRequirementsResolver approvalRequirements,
            IdentityAccessReferenceQuery identities,
            AccessIntentCommand access,
            IdGenerator ids,
            TransactionExecutor transactions) {
        return new AccessRequestService(
                requests,
                approvalRepository,
                approvals,
                eligibility,
                approvalRequirements,
                identities,
                access,
                ids,
                transactions);
    }

    @Bean
    AccessRequestApprovalProcessingService
            accessRequestApprovalProcessingService(
                    JdbcOutboxRepository outbox,
                    ApprovalRepository approvals,
                    AccessRequestRepository requests,
                    AccessRequestService requestService,
                    TransactionExecutor transactions) {
        return new AccessRequestApprovalProcessingService(
                outbox,
                approvals,
                requests,
                requestService,
                transactions);
    }

    @Bean
    AccessRequestApprovalProcessingScheduler
            accessRequestApprovalProcessingScheduler(
                    AccessRequestApprovalProcessingService service) {
        return new AccessRequestApprovalProcessingScheduler(service);
    }

    @Bean
    GovernanceFindingRepository governanceFindingRepository(JdbcTemplate jdbc) {
        return new JdbcGovernanceFindingRepository(jdbc);
    }

    @Bean
    GovernanceObservationReporter governanceObservationReporter(
            GovernanceFindingRepository findings,
            IdGenerator ids,
            TransactionExecutor transactions) {
        return new GovernanceObservationReportingService(
                findings, ids, transactions);
    }

    @Bean
    ObservedAccessDriftEvaluationService observedAccessDriftEvaluationService(
            IntegrationObservedAccessQuery observedAccess,
            PrincipalResolutionQuery principalResolution,
            GovernanceObservationReporter reporter) {
        return new ObservedAccessDriftEvaluationService(
                observedAccess, principalResolution, reporter);
    }

    @Bean
    GovernanceObservationProcessingService governanceObservationProcessingService(
            JdbcOutboxRepository outbox,
            ObservedAccessDriftEvaluationService drift,
            IntegrationObservedAccessQuery observedAccess,
            ObjectMapper objectMapper) {
        return new GovernanceObservationProcessingService(
                outbox, drift, observedAccess, objectMapper);
    }

    @Bean
    GovernanceObservationProcessingScheduler governanceObservationProcessingScheduler(
            GovernanceObservationProcessingService service) {
        return new GovernanceObservationProcessingScheduler(service);
    }
}
