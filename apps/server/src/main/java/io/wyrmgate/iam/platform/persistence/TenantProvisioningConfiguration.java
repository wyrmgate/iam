package io.wyrmgate.iam.platform.persistence;

import io.wyrmgate.iam.platform.tenant.TenantProvisioningRepository;
import io.wyrmgate.iam.platform.tenant.TenantProvisioningService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class TenantProvisioningConfiguration {

    @Bean
    TenantProvisioningRepository tenantProvisioningRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcTenantProvisioningRepository(jdbcTemplate);
    }

    @Bean
    TenantProvisioningService tenantProvisioningService(
            TenantProvisioningRepository repository,
            TransactionExecutor transactions) {
        return new TenantProvisioningService(repository, transactions);
    }
}
