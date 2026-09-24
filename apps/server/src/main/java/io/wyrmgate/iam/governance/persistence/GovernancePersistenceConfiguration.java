package io.wyrmgate.iam.governance.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.governance.application.GovernanceFindingRepository;
import io.wyrmgate.iam.governance.application.GovernanceObservationProcessingScheduler;
import io.wyrmgate.iam.governance.application.GovernanceObservationProcessingService;
import io.wyrmgate.iam.governance.application.GovernanceObservationReporter;
import io.wyrmgate.iam.governance.application.GovernanceObservationReportingService;
import io.wyrmgate.iam.governance.application.ObservedAccessDriftEvaluationService;
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
            GovernanceObservationReporter reporter) {
        return new ObservedAccessDriftEvaluationService(
                observedAccess, reporter);
    }

    @Bean
    GovernanceObservationProcessingService governanceObservationProcessingService(
            JdbcOutboxRepository outbox,
            ObservedAccessDriftEvaluationService drift,
            ObjectMapper objectMapper) {
        return new GovernanceObservationProcessingService(outbox, drift, objectMapper);
    }

    @Bean
    GovernanceObservationProcessingScheduler governanceObservationProcessingScheduler(
            GovernanceObservationProcessingService service) {
        return new GovernanceObservationProcessingScheduler(service);
    }
}
