package io.wyrmgate.iam.governance.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.access.application.AccessIntentCommand;
import io.wyrmgate.iam.governance.application.AccessRequestAccessApplicationScheduler;
import io.wyrmgate.iam.governance.application.AccessRequestAccessApplicationService;
import io.wyrmgate.iam.governance.application.AccessRequestApprovalResultSink;
import io.wyrmgate.iam.governance.application.AccessRequestCommandService;
import io.wyrmgate.iam.governance.application.AccessRequestEligibilityEvaluator;
import io.wyrmgate.iam.governance.application.AccessRequestModels.EligibilityResult;
import io.wyrmgate.iam.governance.application.AccessRequestRepository;
import io.wyrmgate.iam.governance.application.ApprovalCommandService;
import io.wyrmgate.iam.governance.application.ApprovalQueryService;
import io.wyrmgate.iam.governance.application.ApprovalRepository;
import io.wyrmgate.iam.governance.application.ApprovalResultQuery;
import io.wyrmgate.iam.governance.application.ApprovalResultSink;
import io.wyrmgate.iam.governance.application.AuthorizedAccessIntentSink;
import io.wyrmgate.iam.governance.application.GovernanceFindingRepository;
import io.wyrmgate.iam.governance.application.GovernanceObservationProcessingScheduler;
import io.wyrmgate.iam.governance.application.GovernanceObservationProcessingService;
import io.wyrmgate.iam.governance.application.GovernanceObservationReporter;
import io.wyrmgate.iam.governance.application.GovernanceObservationReportingService;
import io.wyrmgate.iam.governance.application.ObservedAccessDriftEvaluationService;
import io.wyrmgate.iam.identity.application.PrincipalResolutionQuery;
import io.wyrmgate.iam.integration.application.IntegrationObservedAccessQuery;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import org.springframework.beans.factory.ObjectProvider;
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
    AccessRequestRepository accessRequestRepository(JdbcTemplate jdbc) {
        return new JdbcAccessRequestRepository(jdbc);
    }

    @Bean
    AuthorizedAccessIntentSink authorizedAccessIntentSink(
            JdbcOutboxRepository outbox,
            IdGenerator ids) {
        return new JdbcAuthorizedAccessIntentSink(outbox, ids);
    }

    @Bean
    ApprovalResultSink approvalResultSink(
            AccessRequestRepository requests,
            AuthorizedAccessIntentSink authorizedAccess) {
        return new AccessRequestApprovalResultSink(
                requests, authorizedAccess);
    }

    @Bean
    ApprovalCommandService approvalCommandService(
            ApprovalRepository approvals,
            ApprovalResultSink resultSink,
            IdGenerator ids,
            TransactionExecutor transactions) {
        return new ApprovalCommandService(
                approvals, resultSink, ids, transactions);
    }

    @Bean
    ApprovalResultQuery approvalResultQuery(
            ApprovalRepository approvals) {
        return new ApprovalQueryService(approvals);
    }

    @Bean
    AccessRequestCommandService accessRequestCommandService(
            AccessRequestRepository requests,
            ObjectProvider<AccessRequestEligibilityEvaluator> evaluators,
            ApprovalCommandService approvals,
            AuthorizedAccessIntentSink authorizedAccess,
            IdGenerator ids,
            TransactionExecutor transactions) {
        AccessRequestEligibilityEvaluator evaluator =
                evaluators.getIfAvailable(() ->
                        (tenant, request, item) ->
                                EligibilityResult.unavailable(
                                        "mandatory_evaluator_unavailable"));
        return new AccessRequestCommandService(
                requests,
                evaluator,
                approvals,
                authorizedAccess,
                ids,
                transactions);
    }

    @Bean
    AccessRequestAccessApplicationService accessRequestAccessApplicationService(
            JdbcOutboxRepository outbox,
            AccessRequestRepository requests,
            AccessIntentCommand access,
            TransactionExecutor transactions) {
        return new AccessRequestAccessApplicationService(
                outbox, requests, access, transactions);
    }

    @Bean
    AccessRequestAccessApplicationScheduler accessRequestAccessApplicationScheduler(
            AccessRequestAccessApplicationService service) {
        return new AccessRequestAccessApplicationScheduler(service);
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
