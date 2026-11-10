package io.wyrmgate.iam.tenancy.persistence;

import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.tenancy.application.TenantCommandService;
import io.wyrmgate.iam.tenancy.application.TenantLifecycleContinuityGuard;
import io.wyrmgate.iam.tenancy.application.TenantOverviewQuery;
import io.wyrmgate.iam.tenancy.application.TenantRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Spring composition for Tenancy-owned persistence and application services. */
@Configuration
public class TenancyPersistenceConfiguration {

    @Bean
    TenantRepository tenantRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcTenantRepository(jdbcTemplate);
    }

    @Bean
    TenantOverviewQuery tenantOverviewQuery(JdbcTemplate jdbcTemplate) {
        return new JdbcTenantOverviewQuery(jdbcTemplate);
    }

    @Bean
    TenantCommandService tenantCommandService(
            TenantRepository repository,
            TenantLifecycleContinuityGuard continuity,
            TransactionExecutor transactions) {
        return new TenantCommandService(repository, continuity, transactions);
    }
}
