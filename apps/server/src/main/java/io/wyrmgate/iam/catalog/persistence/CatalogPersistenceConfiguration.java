package io.wyrmgate.iam.catalog.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.wyrmgate.iam.administration.application.PlatformOwnerAuthorityQuery;
import io.wyrmgate.iam.catalog.application.CatalogAccessTargetAvailabilityFactSink;
import io.wyrmgate.iam.catalog.application.CatalogAccessTargetAvailabilityQuery;
import io.wyrmgate.iam.catalog.application.CatalogAccessTargetAvailabilityQueryService;
import io.wyrmgate.iam.catalog.application.CatalogAccessTargetSearchQuery;
import io.wyrmgate.iam.catalog.application.CatalogAvailabilityPropagationRepository;
import io.wyrmgate.iam.catalog.application.CatalogAvailabilityPropagationService;
import io.wyrmgate.iam.catalog.application.CatalogAvailabilityPropagationStarter;
import io.wyrmgate.iam.catalog.application.CatalogAvailabilityPropagationWorkSink;
import io.wyrmgate.iam.catalog.application.CatalogCommandService;
import io.wyrmgate.iam.catalog.application.CatalogQueryService;
import io.wyrmgate.iam.catalog.application.CatalogRepository;
import io.wyrmgate.iam.catalog.application.PlatformApplicationAdmissionPolicyQuery;
import io.wyrmgate.iam.catalog.application.PlatformApplicationRepository;
import io.wyrmgate.iam.catalog.application.PlatformApplicationService;
import io.wyrmgate.iam.catalog.application.RoleCommandService;
import io.wyrmgate.iam.catalog.application.RoleExpansionFactSink;
import io.wyrmgate.iam.catalog.application.RoleExpansionQuery;
import io.wyrmgate.iam.catalog.application.RoleExpansionQueryService;
import io.wyrmgate.iam.catalog.application.RoleRepository;
import io.wyrmgate.iam.catalog.application.RoleQueryService;
import io.wyrmgate.iam.catalog.application.SsoClientRegistrationRepository;
import io.wyrmgate.iam.catalog.application.SsoClientRegistrationService;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class CatalogPersistenceConfiguration {

    @Bean
    CatalogRepository catalogRepository(JdbcTemplate jdbc) {
        return new JdbcCatalogRepository(jdbc);
    }

    @Bean
    PlatformApplicationRepository platformApplicationRepository(JdbcTemplate jdbc) {
        return new JdbcPlatformApplicationRepository(jdbc);
    }

    @Bean
    @ConditionalOnMissingBean(PlatformApplicationAdmissionPolicyQuery.class)
    PlatformApplicationAdmissionPolicyQuery defaultDenyPlatformApplicationAdmissionPolicyQuery() {
        return (offering, tenant, at) -> false;
    }

    @Bean
    PlatformApplicationService platformApplicationService(
            PlatformApplicationRepository platformApplicationRepository,
            CatalogRepository catalogRepository,
            PlatformOwnerAuthorityQuery platformOwnerAuthorityQuery,
            PlatformApplicationAdmissionPolicyQuery admissionPolicyQuery,
            IdGenerator ids,
            TransactionExecutor transactions) {
        return new PlatformApplicationService(
                platformApplicationRepository,
                catalogRepository,
                platformOwnerAuthorityQuery,
                admissionPolicyQuery,
                ids,
                transactions);
    }

    @Bean
    CatalogAccessTargetSearchQuery catalogAccessTargetSearchQuery(JdbcTemplate jdbc) {
        return new JdbcCatalogAccessTargetSearchQuery(jdbc);
    }

    @Bean
    SsoClientRegistrationRepository ssoClientRegistrationRepository(JdbcTemplate jdbc) {
        return new JdbcSsoClientRegistrationRepository(jdbc);
    }

    @Bean
    SsoClientRegistrationService ssoClientRegistrationService(
            CatalogRepository catalogRepository,
            SsoClientRegistrationRepository ssoClientRegistrationRepository,
            IdGenerator ids,
            TransactionExecutor transactions) {
        return new SsoClientRegistrationService(
                catalogRepository, ssoClientRegistrationRepository, ids, transactions);
    }

    @Bean
    RoleRepository roleRepository(JdbcTemplate jdbc) {
        return new JdbcRoleRepository(jdbc);
    }

    @Bean
    RoleExpansionFactSink roleExpansionFactSink(
            JdbcOutboxRepository outboxRepository,
            IdGenerator ids) {
        return new JdbcRoleExpansionFactSink(outboxRepository, ids);
    }

    @Bean
    CatalogAccessTargetAvailabilityFactSink catalogAccessTargetAvailabilityFactSink(
            JdbcOutboxRepository outboxRepository,
            IdGenerator ids,
            ObjectMapper objectMapper) {
        return new JdbcCatalogAccessTargetAvailabilityFactSink(
                outboxRepository, ids, objectMapper);
    }

    @Bean
    CatalogAvailabilityPropagationRepository catalogAvailabilityPropagationRepository(
            JdbcTemplate jdbc) {
        return new JdbcCatalogAvailabilityPropagationRepository(jdbc);
    }

    @Bean
    CatalogAvailabilityPropagationWorkSink catalogAvailabilityPropagationWorkSink(
            JdbcOutboxRepository outboxRepository,
            IdGenerator ids) {
        return new JdbcCatalogAvailabilityPropagationWorkSink(
                outboxRepository, ids);
    }

    @Bean
    CatalogAvailabilityPropagationStarter catalogAvailabilityPropagationStarter(
            CatalogAvailabilityPropagationRepository repository,
            CatalogAvailabilityPropagationWorkSink work,
            IdGenerator ids,
            TransactionExecutor transactions) {
        return new CatalogAvailabilityPropagationStarter(
                repository, work, ids, transactions);
    }

    @Bean
    RoleQueryService roleQueryService(RoleRepository roleRepository) {
        return new RoleQueryService(roleRepository);
    }

    @Bean
    RoleExpansionQuery roleExpansionQuery(
            CatalogRepository catalogRepository,
            RoleRepository roleRepository) {
        return new RoleExpansionQueryService(
                catalogRepository, roleRepository);
    }

    @Bean
    CatalogAccessTargetAvailabilityQuery catalogAccessTargetAvailabilityQuery(
            CatalogRepository catalogRepository,
            RoleRepository roleRepository,
            RoleExpansionQuery roleExpansionQuery) {
        return new CatalogAccessTargetAvailabilityQueryService(
                catalogRepository, roleRepository, roleExpansionQuery);
    }

    @Bean
    CatalogAvailabilityPropagationService catalogAvailabilityPropagationService(
            JdbcOutboxRepository outboxRepository,
            CatalogAvailabilityPropagationRepository propagationRepository,
            CatalogAccessTargetAvailabilityFactSink availabilityFacts,
            RoleExpansionFactSink roleFacts,
            CatalogAvailabilityPropagationWorkSink work,
            TransactionExecutor transactions) {
        return new CatalogAvailabilityPropagationService(
                outboxRepository,
                propagationRepository,
                availabilityFacts,
                roleFacts,
                work,
                transactions);
    }

    @Bean
    CatalogAvailabilityPropagationScheduler catalogAvailabilityPropagationScheduler(
            CatalogAvailabilityPropagationService service) {
        return new CatalogAvailabilityPropagationScheduler(service);
    }

    @Bean
    RoleCommandService roleCommandService(
            CatalogRepository catalogRepository,
            RoleRepository roleRepository,
            RoleExpansionFactSink roleExpansionFactSink,
            CatalogAvailabilityPropagationStarter availabilityPropagation,
            IdGenerator ids,
            TransactionExecutor transactions) {
        return new RoleCommandService(
                catalogRepository,
                roleRepository,
                roleExpansionFactSink,
                availabilityPropagation,
                ids,
                transactions);
    }

    @Bean
    CatalogCommandService catalogCommandService(
            CatalogRepository repository,
            CatalogAvailabilityPropagationStarter availabilityPropagation,
            IdGenerator ids,
            TransactionExecutor transactions) {
        return new CatalogCommandService(
                repository, availabilityPropagation, ids, transactions);
    }

    @Bean
    CatalogQueryService catalogQueryService(CatalogRepository repository) {
        return new CatalogQueryService(repository);
    }
}
