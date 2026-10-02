package io.wyrmgate.iam.administration.persistence;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorityRepository;
import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassAuditSink;
import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassPolicy;
import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassNotificationScheduler;
import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassRepository;
import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassService;
import io.wyrmgate.iam.administration.application.AdministrativeAuthorityService;
import io.wyrmgate.iam.administration.application.AdministrativeElevationApprovalCommand;
import io.wyrmgate.iam.administration.application.AdministrativeElevationRepository;
import io.wyrmgate.iam.administration.application.AdministrativeElevationService;
import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationRepository;
import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.ControlPlaneActorBindingRepository;
import io.wyrmgate.iam.administration.application.ControlPlaneActorResolver;
import io.wyrmgate.iam.administration.application.GovernedActorStatusQuery;
import io.wyrmgate.iam.administration.application.InitialAdminBootstrapFactSink;
import io.wyrmgate.iam.administration.application.InitialAdminBootstrapRepository;
import io.wyrmgate.iam.administration.application.InitialAdminBootstrapService;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Spring composition for Administration authorization, actor resolution and bootstrap persistence. */
@Configuration
public class AdministrationPersistenceConfiguration {

    @Bean
    AdministrativeAuthorizationRepository administrativeAuthorizationRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcAdministrativeAuthorizationRepository(jdbcTemplate);
    }

    @Bean
    AdministrativeAuthorizationService administrativeAuthorizationService(
            AdministrativeAuthorizationRepository repository,
            GovernedActorStatusQuery governedActorStatusQuery) {
        return new AdministrativeAuthorizationService(repository, governedActorStatusQuery);
    }

    @Bean
    AdministrativeAuthorityRepository administrativeAuthorityRepository(
            JdbcTemplate jdbcTemplate, IdGenerator idGenerator) {
        return new JdbcAdministrativeAuthorityRepository(jdbcTemplate, idGenerator);
    }

    @Bean
    AdministrativeAuthorityService administrativeAuthorityService(
            AdministrativeAuthorityRepository repository,
            AdministrativeAuthorizationService authorization,
            GovernedActorStatusQuery governedActorStatusQuery,
            IdGenerator idGenerator,
            TransactionExecutor transactionExecutor) {
        return new AdministrativeAuthorityService(
                repository,
                authorization,
                governedActorStatusQuery,
                idGenerator,
                transactionExecutor);
    }

    @Bean
    AdministrativeBreakGlassRepository administrativeBreakGlassRepository(
            JdbcTemplate jdbcTemplate) {
        return new JdbcAdministrativeBreakGlassRepository(jdbcTemplate);
    }

    @Bean
    AdministrativeBreakGlassNotificationScheduler administrativeBreakGlassNotificationScheduler(
            JdbcScheduledWorkRepository scheduledWork) {
        return new JdbcAdministrativeBreakGlassNotificationScheduler(scheduledWork);
    }

    @Bean
    @ConditionalOnMissingBean(AdministrativeBreakGlassPolicy.class)
    AdministrativeBreakGlassPolicy defaultDenyAdministrativeBreakGlassPolicy() {
        return (tenant, actorIdentityId, requestedRole, requestedScope, requestedValidUntil, now) ->
                AdministrativeBreakGlassPolicy.Decision.deny(
                        "break_glass_policy_unconfigured");
    }

    @Bean
    AdministrativeBreakGlassService administrativeBreakGlassService(
            AdministrativeBreakGlassRepository repository,
            AdministrativeAuthorityRepository authority,
            AdministrativeAuthorizationService authorization,
            GovernedActorStatusQuery governedActorStatusQuery,
            AdministrativeBreakGlassPolicy policy,
            AdministrativeBreakGlassAuditSink audit,
            AdministrativeBreakGlassNotificationScheduler notificationScheduler,
            IdGenerator idGenerator,
            TransactionExecutor transactionExecutor) {
        return new AdministrativeBreakGlassService(
                repository,
                authority,
                authorization,
                governedActorStatusQuery,
                policy,
                audit,
                notificationScheduler,
                idGenerator,
                transactionExecutor);
    }

    @Bean
    AdministrativeElevationRepository administrativeElevationRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcAdministrativeElevationRepository(jdbcTemplate);
    }

    @Bean
    AdministrativeElevationService administrativeElevationService(
            AdministrativeElevationRepository elevations,
            AdministrativeAuthorityRepository authority,
            AdministrativeAuthorizationService authorization,
            GovernedActorStatusQuery governedActorStatusQuery,
            AdministrativeElevationApprovalCommand approvals,
            IdGenerator idGenerator,
            TransactionExecutor transactionExecutor) {
        return new AdministrativeElevationService(
                elevations,
                authority,
                authorization,
                governedActorStatusQuery,
                approvals,
                idGenerator,
                transactionExecutor);
    }

    @Bean
    ControlPlaneActorBindingRepository controlPlaneActorBindingRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcControlPlaneActorBindingRepository(jdbcTemplate);
    }

    @Bean
    ControlPlaneActorResolver controlPlaneActorResolver(ControlPlaneActorBindingRepository repository) {
        return new ControlPlaneActorResolver(repository);
    }

    @Bean
    InitialAdminBootstrapRepository initialAdminBootstrapRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcInitialAdminBootstrapRepository(jdbcTemplate);
    }

    @Bean
    InitialAdminBootstrapFactSink initialAdminBootstrapFactSink(
            JdbcOutboxRepository outboxRepository,
            IdGenerator idGenerator) {
        return new JdbcInitialAdminBootstrapFactSink(outboxRepository, idGenerator);
    }

    @Bean
    InitialAdminBootstrapService initialAdminBootstrapService(
            InitialAdminBootstrapRepository bootstrapRepository,
            ControlPlaneActorBindingRepository actorBindingRepository,
            GovernedActorStatusQuery governedActorStatusQuery,
            InitialAdminBootstrapFactSink factSink,
            IdGenerator idGenerator,
            TransactionExecutor transactionExecutor) {
        return new InitialAdminBootstrapService(
                bootstrapRepository,
                actorBindingRepository,
                governedActorStatusQuery,
                factSink,
                idGenerator,
                transactionExecutor);
    }
}
