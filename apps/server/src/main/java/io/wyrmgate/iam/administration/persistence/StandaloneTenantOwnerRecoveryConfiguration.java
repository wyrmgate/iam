package io.wyrmgate.iam.administration.persistence;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorityRepository;
import io.wyrmgate.iam.administration.application.FirstPartyInitialAdminAnchorQuery;
import io.wyrmgate.iam.administration.application.GovernedActorStatusQuery;
import io.wyrmgate.iam.administration.application.InitialAdminBootstrapRepository;
import io.wyrmgate.iam.administration.application.StandaloneTenantOwnerRecoveryRepository;
import io.wyrmgate.iam.administration.application.StandaloneTenantOwnerRecoveryService;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Spring composition for the explicit ADR-0047 compatibility operation. */
@Configuration("administrationStandaloneTenantOwnerRecoveryConfiguration")
public class StandaloneTenantOwnerRecoveryConfiguration {

    @Bean
    StandaloneTenantOwnerRecoveryRepository standaloneTenantOwnerRecoveryRepository(
            JdbcTemplate jdbcTemplate,
            IdGenerator idGenerator) {
        return new JdbcStandaloneTenantOwnerRecoveryRepository(jdbcTemplate, idGenerator);
    }

    @Bean
    StandaloneTenantOwnerRecoveryService standaloneTenantOwnerRecoveryService(
            InitialAdminBootstrapRepository bootstrapRepository,
            AdministrativeAuthorityRepository authorityRepository,
            StandaloneTenantOwnerRecoveryRepository recoveryRepository,
            GovernedActorStatusQuery governedActorStatusQuery,
            FirstPartyInitialAdminAnchorQuery firstPartyAnchorQuery,
            TransactionExecutor transactionExecutor) {
        return new StandaloneTenantOwnerRecoveryService(
                bootstrapRepository,
                authorityRepository,
                recoveryRepository,
                governedActorStatusQuery,
                firstPartyAnchorQuery,
                transactionExecutor);
    }
}
