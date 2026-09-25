package io.wyrmgate.iam.catalog.persistence;

import io.wyrmgate.iam.catalog.application.CatalogCommandService;
import io.wyrmgate.iam.catalog.application.CatalogQueryService;
import io.wyrmgate.iam.catalog.application.CatalogRepository;
import io.wyrmgate.iam.catalog.application.RoleCommandService;
import io.wyrmgate.iam.catalog.application.RoleExpansionFactSink;
import io.wyrmgate.iam.catalog.application.RoleExpansionQuery;
import io.wyrmgate.iam.catalog.application.RoleExpansionQueryService;
import io.wyrmgate.iam.catalog.application.RoleRepository;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
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
    RoleExpansionQuery roleExpansionQuery(
            CatalogRepository catalogRepository,
            RoleRepository roleRepository) {
        return new RoleExpansionQueryService(
                catalogRepository, roleRepository);
    }

    @Bean
    RoleCommandService roleCommandService(
            CatalogRepository catalogRepository,
            RoleRepository roleRepository,
            RoleExpansionFactSink roleExpansionFactSink,
            IdGenerator ids,
            TransactionExecutor transactions) {
        return new RoleCommandService(
                catalogRepository,
                roleRepository,
                roleExpansionFactSink,
                ids,
                transactions);
    }

    @Bean
    CatalogCommandService catalogCommandService(
            CatalogRepository repository,
            IdGenerator ids,
            TransactionExecutor transactions) {
        return new CatalogCommandService(repository, ids, transactions);
    }

    @Bean
    CatalogQueryService catalogQueryService(CatalogRepository repository) {
        return new CatalogQueryService(repository);
    }
}
