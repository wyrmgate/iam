package io.wyrmgate.iam.governance.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.access.application.AccessIntentCommand;
import io.wyrmgate.iam.access.application.EffectiveAccessQuery;
import io.wyrmgate.iam.catalog.application.CatalogAccessReferenceQuery;
import io.wyrmgate.iam.catalog.application.RoleExpansionQuery;
import io.wyrmgate.iam.governance.application.AccessRequestAccessApplicationScheduler;
import io.wyrmgate.iam.governance.application.AccessRequestAccessApplicationService;
import io.wyrmgate.iam.governance.application.AccessRequestApprovalResultSink;
import io.wyrmgate.iam.governance.application.AccessRequestCommandService;
import io.wyrmgate.iam.governance.application.AccessRequestEvaluationProcessingScheduler;
import io.wyrmgate.iam.governance.application.AccessRequestEvaluationProcessingService;
import io.wyrmgate.iam.governance.application.AccessRequestEligibilityEvaluator;
import io.wyrmgate.iam.governance.application.AccessRequestModels.EligibilityResult;
import io.wyrmgate.iam.governance.application.AccessRequestRepository;
import io.wyrmgate.iam.governance.application.ApprovalCaseStartService;
import io.wyrmgate.iam.governance.application.ApprovalCommandService;
import io.wyrmgate.iam.governance.application.CompositeApprovalResultSink;
import io.wyrmgate.iam.governance.application.ApprovalQueryService;
import io.wyrmgate.iam.governance.application.ApprovalRepository;
import io.wyrmgate.iam.governance.application.ApprovalResultQuery;
import io.wyrmgate.iam.governance.application.ApprovalResultSink;
import io.wyrmgate.iam.governance.application.AuthorizedAccessIntentSink;
import io.wyrmgate.iam.governance.application.SubmittedRequestItemSink;
import io.wyrmgate.iam.governance.application.GovernanceExceptionApprovalResultSink;
import io.wyrmgate.iam.governance.application.GovernanceExceptionBoundaryScheduler;
import io.wyrmgate.iam.governance.application.GovernanceExceptionChangeSink;
import io.wyrmgate.iam.governance.application.GovernanceExceptionExpiryProcessingScheduler;
import io.wyrmgate.iam.governance.application.GovernanceExceptionExpiryProcessingService;
import io.wyrmgate.iam.governance.application.GovernanceExceptionQuery;
import io.wyrmgate.iam.governance.application.GovernanceExceptionRepository;
import io.wyrmgate.iam.governance.application.GovernanceExceptionService;
import io.wyrmgate.iam.governance.application.GovernanceFindingRepository;
import io.wyrmgate.iam.governance.application.GovernancePolicyEligibilityEvaluator;
import io.wyrmgate.iam.governance.application.GovernancePolicyRepository;
import io.wyrmgate.iam.governance.application.GovernancePolicyService;
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
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
    SubmittedRequestItemSink submittedRequestItemSink(
            JdbcOutboxRepository outbox,
            IdGenerator ids) {
        return new JdbcSubmittedRequestItemSink(outbox, ids);
    }

    @Bean
    AuthorizedAccessIntentSink authorizedAccessIntentSink(
            JdbcOutboxRepository outbox,
            IdGenerator ids) {
        return new JdbcAuthorizedAccessIntentSink(outbox, ids);
    }

    @Bean
    GovernancePolicyRepository governancePolicyRepository(
            JdbcTemplate jdbc) {
        return new JdbcGovernancePolicyRepository(jdbc);
    }

    @Bean
    GovernancePolicyService governancePolicyService(
            GovernancePolicyRepository policies,
            CatalogAccessReferenceQuery catalog,
            IdentityAccessReferenceQuery identities,
            IdGenerator ids,
            TransactionExecutor transactions) {
        return new GovernancePolicyService(
                policies, catalog, identities, ids, transactions);
    }

    @Bean
    AccessRequestEligibilityEvaluator accessRequestEligibilityEvaluator(
            GovernancePolicyService policyService,
            GovernancePolicyRepository policies,
            AccessRequestRepository requests,
            CatalogAccessReferenceQuery catalog,
            RoleExpansionQuery roleExpansion,
            EffectiveAccessQuery effectiveAccess,
            GovernanceExceptionQuery exceptions,
            IdGenerator ids,
            TransactionExecutor transactions) {
        return new GovernancePolicyEligibilityEvaluator(
                policyService,
                policies,
                requests,
                catalog,
                roleExpansion,
                effectiveAccess,
                exceptions,
                ids,
                transactions);
    }

    @Bean
    ApprovalCaseStartService approvalCaseStartService(
            ApprovalRepository approvals,
            IdGenerator ids,
            TransactionExecutor transactions) {
        return new ApprovalCaseStartService(
                approvals, ids, transactions);
    }

    @Bean
    GovernanceExceptionRepository governanceExceptionRepository(
            JdbcTemplate jdbc) {
        return new JdbcGovernanceExceptionRepository(jdbc);
    }

    @Bean
    GovernanceExceptionChangeSink governanceExceptionChangeSink(
            JdbcOutboxRepository outbox,
            IdGenerator ids) {
        return new JdbcGovernanceExceptionChangeSink(
                outbox, ids);
    }

    @Bean
    GovernanceExceptionBoundaryScheduler governanceExceptionBoundaryScheduler(
            JdbcScheduledWorkRepository scheduledWork) {
        return new JdbcGovernanceExceptionBoundaryScheduler(
                scheduledWork);
    }

    @Bean
    GovernanceExceptionService governanceExceptionService(
            GovernanceExceptionRepository exceptions,
            GovernancePolicyRepository policies,
            IdentityAccessReferenceQuery identities,
            ApprovalCaseStartService starter,
            GovernanceExceptionChangeSink changes,
            GovernanceExceptionBoundaryScheduler boundaries,
            IdGenerator ids,
            TransactionExecutor transactions) {
        return new GovernanceExceptionService(
                exceptions,
                policies,
                identities,
                starter,
                changes,
                boundaries,
                ids,
                transactions);
    }

    @Bean
    GovernanceExceptionExpiryProcessingService governanceExceptionExpiryProcessingService(
            JdbcScheduledWorkRepository scheduledWork,
            GovernanceExceptionService exceptions) {
        return new GovernanceExceptionExpiryProcessingService(
                scheduledWork, exceptions);
    }

    @Bean
    GovernanceExceptionExpiryProcessingScheduler governanceExceptionExpiryProcessingScheduler(
            GovernanceExceptionExpiryProcessingService service) {
        return new GovernanceExceptionExpiryProcessingScheduler(
                service);
    }

    @Bean
    AccessRequestApprovalResultSink accessRequestApprovalResultSink(
            AccessRequestRepository requests,
            AuthorizedAccessIntentSink authorizedAccess,
            AccessRequestEligibilityEvaluator evaluator,
            ApprovalRepository approvals,
            ApprovalCaseStartService starter,
            SubmittedRequestItemSink submittedItems) {
        return new AccessRequestApprovalResultSink(
                requests,
                authorizedAccess,
                evaluator,
                approvals,
                starter,
                submittedItems);
    }

    @Bean
    GovernanceExceptionApprovalResultSink governanceExceptionApprovalResultSink(
            GovernanceExceptionService exceptions) {
        return new GovernanceExceptionApprovalResultSink(
                exceptions);
    }

    @Bean
    @Primary
    ApprovalResultSink approvalResultSink(
            AccessRequestApprovalResultSink accessRequests,
            GovernanceExceptionApprovalResultSink exceptions) {
        return new CompositeApprovalResultSink(
                java.util.List.of(
                        accessRequests,
                        exceptions));
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
    ApprovalQueryService approvalResultQuery(
            ApprovalRepository approvals) {
        return new ApprovalQueryService(approvals);
    }

    @Bean
    AccessRequestCommandService accessRequestCommandService(
            AccessRequestRepository requests,
            ObjectProvider<AccessRequestEligibilityEvaluator> evaluators,
            ApprovalCommandService approvals,
            SubmittedRequestItemSink submittedItems,
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
                submittedItems,
                authorizedAccess,
                ids,
                transactions);
    }

    @Bean
    AccessRequestEvaluationProcessingService accessRequestEvaluationProcessingService(
            JdbcOutboxRepository outbox,
            AccessRequestRepository requests,
            AccessRequestCommandService commands) {
        return new AccessRequestEvaluationProcessingService(
                outbox, requests, commands);
    }

    @Bean
    AccessRequestEvaluationProcessingScheduler accessRequestEvaluationProcessingScheduler(
            AccessRequestEvaluationProcessingService service) {
        return new AccessRequestEvaluationProcessingScheduler(service);
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
