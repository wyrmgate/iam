package io.wyrmgate.iam.catalog.persistence;

import io.wyrmgate.iam.catalog.application.CatalogCommandService;
import io.wyrmgate.iam.catalog.application.CatalogQueryService;
import io.wyrmgate.iam.catalog.application.CatalogRepository;
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
