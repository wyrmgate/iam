package io.wyrmgate.iam.identity.persistence;

import io.wyrmgate.iam.administration.application.GovernedActorStatusQuery;
import io.wyrmgate.iam.catalog.application.CatalogTargetReferenceQuery;
import io.wyrmgate.iam.identity.application.CanonicalAttributeConfigurationService;
import io.wyrmgate.iam.identity.application.CanonicalAttributeFactSink;
import io.wyrmgate.iam.identity.application.CanonicalAttributeReadRepository;
import io.wyrmgate.iam.identity.application.CanonicalAttributeRepository;
import io.wyrmgate.iam.identity.application.CanonicalAttributeResolutionEvaluator;
import io.wyrmgate.iam.identity.application.CanonicalAttributeResolutionService;
import io.wyrmgate.iam.identity.application.IdentityCommandService;
import io.wyrmgate.iam.identity.application.IdentityFactSink;
import io.wyrmgate.iam.identity.application.IdentityQueryRepository;
import io.wyrmgate.iam.identity.application.IdentityQueryService;
import io.wyrmgate.iam.identity.application.IdentityRepository;
import io.wyrmgate.iam.identity.application.PrincipalCommandService;
import io.wyrmgate.iam.identity.application.PrincipalFactSink;
import io.wyrmgate.iam.identity.application.PrincipalQueryService;
import io.wyrmgate.iam.identity.application.PrincipalRepository;
import io.wyrmgate.iam.identity.application.SourceCorrelationFactSink;
import io.wyrmgate.iam.identity.application.SourceCorrelationRepository;
import io.wyrmgate.iam.identity.application.SourceCorrelationService;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Spring composition for Identity persistence adapters, queries and application commands. */
@Configuration
public class IdentityPersistenceConfiguration {

    @Bean
    IdentityRepository identityRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcIdentityRepository(jdbcTemplate);
    }

    @Bean
    IdentityQueryRepository identityQueryRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcIdentityQueryRepository(jdbcTemplate);
    }

    @Bean
    CanonicalAttributeReadRepository canonicalAttributeReadRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcCanonicalAttributeReadRepository(jdbcTemplate);
    }

    @Bean
    CanonicalAttributeResolutionEvaluator canonicalAttributeResolutionEvaluator() {
        return new CanonicalAttributeResolutionEvaluator();
    }

    @Bean
    IdentityQueryService identityQueryService(
            IdentityRepository identityRepository,
            IdentityQueryRepository identityQueryRepository,
            CanonicalAttributeRepository canonicalAttributeRepository,
            CanonicalAttributeReadRepository canonicalAttributeReadRepository,
            CanonicalAttributeResolutionEvaluator evaluator) {
        return new IdentityQueryService(
                identityRepository,
                identityQueryRepository,
                canonicalAttributeRepository,
                canonicalAttributeReadRepository,
                evaluator);
    }

    @Bean
    GovernedActorStatusQuery governedActorStatusQuery(IdentityRepository identityRepository) {
        return new IdentityGovernedActorStatusQuery(identityRepository);
    }

    @Bean
    IdentityFactSink identityFactSink(JdbcOutboxRepository outboxRepository, IdGenerator idGenerator) {
        return new JdbcIdentityFactSink(outboxRepository, idGenerator);
    }

    @Bean
    IdentityCommandService identityCommandService(
            IdentityRepository identityRepository,
            IdentityFactSink identityFactSink,
            IdGenerator idGenerator,
            TransactionExecutor transactionExecutor) {
        return new IdentityCommandService(identityRepository, identityFactSink, idGenerator, transactionExecutor);
    }

    @Bean
    PrincipalRepository principalRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcPrincipalRepository(jdbcTemplate);
    }

    @Bean
    PrincipalFactSink principalFactSink(
            JdbcOutboxRepository outboxRepository, IdGenerator idGenerator) {
        return new JdbcPrincipalFactSink(outboxRepository, idGenerator);
    }

    @Bean
    PrincipalQueryService principalQueryService(PrincipalRepository principalRepository) {
        return new PrincipalQueryService(principalRepository);
    }

    @Bean
    PrincipalCommandService principalCommandService(
            PrincipalRepository principalRepository,
            IdentityRepository identityRepository,
            CatalogTargetReferenceQuery targetReferenceQuery,
            PrincipalFactSink principalFactSink,
            IdGenerator idGenerator,
            TransactionExecutor transactionExecutor) {
        return new PrincipalCommandService(
                principalRepository,
                identityRepository,
                targetReferenceQuery,
                principalFactSink,
                idGenerator,
                transactionExecutor);
    }

    @Bean
    SourceCorrelationRepository sourceCorrelationRepository(JdbcTemplate jdbcTemplate, IdGenerator idGenerator) {
        return new JdbcSourceCorrelationRepository(jdbcTemplate, idGenerator);
    }

    @Bean
    SourceCorrelationFactSink sourceCorrelationFactSink(
            JdbcOutboxRepository outboxRepository, IdGenerator idGenerator) {
        return new JdbcSourceCorrelationFactSink(outboxRepository, idGenerator);
    }

    @Bean
    SourceCorrelationService sourceCorrelationService(
            SourceCorrelationRepository sourceCorrelationRepository,
            IdentityRepository identityRepository,
            SourceCorrelationFactSink sourceCorrelationFactSink,
            IdGenerator idGenerator,
            TransactionExecutor transactionExecutor) {
        return new SourceCorrelationService(
                sourceCorrelationRepository, identityRepository, sourceCorrelationFactSink,
                idGenerator, transactionExecutor);
    }

    @Bean
    CanonicalAttributeRepository canonicalAttributeRepository(JdbcTemplate jdbcTemplate, IdGenerator idGenerator) {
        return new JdbcCanonicalAttributeRepository(jdbcTemplate, idGenerator);
    }

    @Bean
    CanonicalAttributeFactSink canonicalAttributeFactSink(
            JdbcOutboxRepository outboxRepository, IdGenerator idGenerator) {
        return new JdbcCanonicalAttributeFactSink(outboxRepository, idGenerator);
    }

    @Bean
    CanonicalAttributeConfigurationService canonicalAttributeConfigurationService(
            CanonicalAttributeRepository canonicalAttributeRepository,
            SourceCorrelationRepository sourceCorrelationRepository,
            CanonicalAttributeFactSink canonicalAttributeFactSink,
            IdGenerator idGenerator,
            TransactionExecutor transactionExecutor) {
        return new CanonicalAttributeConfigurationService(
                canonicalAttributeRepository, sourceCorrelationRepository, canonicalAttributeFactSink,
                idGenerator, transactionExecutor);
    }

    @Bean
    CanonicalAttributeResolutionService canonicalAttributeResolutionService(
            CanonicalAttributeRepository canonicalAttributeRepository,
            SourceCorrelationRepository sourceCorrelationRepository,
            IdentityRepository identityRepository,
            CanonicalAttributeFactSink canonicalAttributeFactSink,
            IdGenerator idGenerator,
            TransactionExecutor transactionExecutor,
            CanonicalAttributeResolutionEvaluator evaluator) {
        return new CanonicalAttributeResolutionService(
                canonicalAttributeRepository,
                sourceCorrelationRepository,
                identityRepository,
                canonicalAttributeFactSink,
                idGenerator,
                transactionExecutor,
                evaluator);
    }
}
