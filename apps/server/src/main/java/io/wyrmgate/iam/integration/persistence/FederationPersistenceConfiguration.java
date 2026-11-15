package io.wyrmgate.iam.integration.persistence;
import io.wyrmgate.iam.integration.application.federation.*;
import io.wyrmgate.iam.identity.application.IdentityAuthenticationQuery;
import io.wyrmgate.iam.tenancy.application.TenantOverviewQuery;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods=false)
public class FederationPersistenceConfiguration {
    @Bean FederationTrustRepository federationTrustRepository(JdbcTemplate jdbc){return new JdbcFederationTrustRepository(jdbc);}
    @Bean FederationTrustCommands federationTrustCommands(FederationTrustRepository repo,IdentityAuthenticationQuery identity,
            TenantOverviewQuery tenants,IdGenerator ids,TransactionExecutor tx){return new FederationTrustCommands(repo,identity,tenants,ids,tx);}
}
