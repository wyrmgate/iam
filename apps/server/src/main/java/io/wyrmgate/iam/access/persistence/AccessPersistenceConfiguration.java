package io.wyrmgate.iam.access.persistence;

import io.wyrmgate.iam.access.application.AccessAssignmentCommandService;
import io.wyrmgate.iam.access.application.AccessAssignmentRepository;
import io.wyrmgate.iam.access.application.AccessDesiredStateQueryService;
import io.wyrmgate.iam.access.application.DesiredAccessStateQuery;
import io.wyrmgate.iam.access.application.DesiredStateProjectionRepository;
import io.wyrmgate.iam.catalog.application.CatalogAccessReferenceQuery;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQuery;
import io.wyrmgate.iam.platform.id.IdGenerator;
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
            IdGenerator idGenerator,
            TransactionExecutor transactionExecutor) {
        return new AccessAssignmentCommandService(
                repository,
                identityReferences,
                catalogReferences,
                idGenerator,
                transactionExecutor);
    }

    @Bean
    DesiredStateProjectionRepository desiredStateProjectionRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcDesiredStateProjectionRepository(jdbcTemplate);
    }

    @Bean
    DesiredAccessStateQuery desiredAccessStateQuery(DesiredStateProjectionRepository repository) {
        return new AccessDesiredStateQueryService(repository);
    }
}
