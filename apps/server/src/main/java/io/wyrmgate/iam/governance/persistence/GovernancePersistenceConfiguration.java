package io.wyrmgate.iam.governance.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.access.application.AccessIntentCommand;
import io.wyrmgate.iam.governance.application.AccessRequestApprovalOutcomeProcessingScheduler;
import io.wyrmgate.iam.governance.application.AccessRequestApprovalOutcomeProcessingService;
import io.wyrmgate.iam.governance.application.AccessRequestEligibilityEvaluator;
import io.wyrmgate.iam.governance.application.AccessRequestRepository;
import io.wyrmgate.iam.governance.application.AccessRequestService;
import io.wyrmgate.iam.governance.application.ApprovalOutcomeFactSink;
import io.wyrmgate.iam.governance.application.ApprovalQueryService;
import io.wyrmgate.iam.governance.application.ApprovalRepository;
import io.wyrmgate.iam.governance.application.ApprovalService;
import io.wyrmgate.iam.governance.application.FailClosedAccessRequestEligibilityEvaluator;
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
    ApprovalOutcomeFactSink approvalOutcomeFactSink(
            JdbcOutboxRepository outbox,
            IdGenerator ids) {
        return new JdbcApprovalOutcomeFactSink(outbox, ids);
    }

    @Bean
    ApprovalService approvalService(
            ApprovalRepository repository,
            ApprovalOutcomeFactSink outcomes,
            IdGenerator ids,
            TransactionExecutor transactions) {
        return new ApprovalService(
                repository, outcomes, ids, transactions);
    }

    @Bean
    ApprovalQueryService approvalQueryService(
            ApprovalRepository repository) {
        return new ApprovalQueryService(repository);
    }

    @Bean
    AccessRequestRepository accessRequestRepository(
            JdbcTemplate jdbc) {
        return new JdbcAccessRequestRepository(jdbc);
    }

    @Bean
    AccessRequestEligibilityEvaluator
            accessRequestEligibilityEvaluator() {
        return new FailClosedAccessRequestEligibilityEvaluator();
    }

    @Bean
    AccessRequestService accessRequestService(
            AccessRequestRepository requests,
            AccessRequestEligibilityEvaluator evaluator,
            ApprovalService approvals,
            IdentityAccessReferenceQuery identities,
            AccessIntentCommand accessIntent,
            IdGenerator ids,
            TransactionExecutor transactions) {
        return new AccessRequestService(
                requests,
                evaluator,
                approvals,
                identities,
                accessIntent,
                ids,
                transactions);
    }

    @Bean
    AccessRequestApprovalOutcomeProcessingService
            accessRequestApprovalOutcomeProcessingService(
                    JdbcOutboxRepository outbox,
                    AccessRequestService requests,
                    ObjectMapper objectMapper) {
        return new AccessRequestApprovalOutcomeProcessingService(
                outbox, requests, objectMapper);
    }

    @Bean
    AccessRequestApprovalOutcomeProcessingScheduler
            accessRequestApprovalOutcomeProcessingScheduler(
                    AccessRequestApprovalOutcomeProcessingService service) {
        return new AccessRequestApprovalOutcomeProcessingScheduler(
                service);
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
