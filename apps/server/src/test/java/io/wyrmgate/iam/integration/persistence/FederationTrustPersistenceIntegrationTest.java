package io.wyrmgate.iam.integration.persistence;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.wyrmgate.iam.integration.application.federation.*;
import io.wyrmgate.iam.identity.application.IdentityAuthenticationQuery;
import io.wyrmgate.iam.platform.persistence.*;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import io.wyrmgate.iam.tenancy.application.TenantOverviewQuery;
import io.wyrmgate.iam.tenancy.domain.TenantLifecycleState;
import io.wyrmgate.iam.tenancy.persistence.TenantTestFixture;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class FederationTrustPersistenceIntegrationTest {
    @Test void exactTenantSubjectHistoryAndRetirement() throws Exception {
        var ds=SharedPostgresIntegrationDatabase.dataSource();var jdbc=new JdbcTemplate(ds);
        var tx=new SpringTransactionExecutor(new DataSourceTransactionManager(ds));
        var fixture=new TenantTestFixture(jdbc,UUID::randomUUID);Instant now=Instant.now();
        var tenant=new TenantContext(fixture.createActive("federation-a",now).id());
        var other=new TenantContext(fixture.createActive("federation-b",now).id());
        var repository=new JdbcFederationTrustRepository(jdbc);
        var tenants=mock(TenantOverviewQuery.class);var identities=mock(IdentityAuthenticationQuery.class);
        when(tenants.find(tenant.tenantId())).thenReturn(Optional.of(new TenantOverviewQuery.TenantSummary(tenant.tenantId(),"A",TenantLifecycleState.ACTIVE,1,now,now)));
        UUID principal=UUID.randomUUID(), identity=UUID.randomUUID();
        when(identities.eligibleSubject(tenant,principal)).thenReturn(Optional.of(new IdentityAuthenticationQuery.AuthenticationSubject(principal,identity,1,1)));
        var commands=new FederationTrustCommands(repository,identities,tenants,UUID::randomUUID,tx,mock(io.wyrmgate.iam.catalog.application.SsoClientProtocolQuery.class));
        var k=KeyPairGenerator.getInstance("RSA");k.initialize(2048);
        var settings=new OidcTrustSettings("https://issuer.example/authorize","https://issuer.example/token","key",
            Base64.getEncoder().encodeToString(k.generateKeyPair().getPublic().getEncoded()));
        var trust=commands.createOidc(tenant,"https://issuer.example","client",settings,now);
        var link=commands.link(tenant,trust.trust().id(),"Subject",principal,now);
        assertThat(repository.activeLink(tenant,trust.trust().id(),"Subject")).contains(link.link());
        assertThat(repository.activeLink(tenant,trust.trust().id(),"subject")).isEmpty();
        assertThat(repository.activeTrust(other,trust.trust().id())).isEmpty();
        assertThat(repository.activeLinkById(other,link.link().id())).isEmpty();
        assertThatThrownBy(()->commands.link(tenant,trust.trust().id(),"Subject",principal,now)).isInstanceOf(io.wyrmgate.iam.integration.application.IntegrationAdministrationException.class);
        commands.retireLink(tenant,link.link().id(),1,now.plusSeconds(1));
        var replacement=commands.link(tenant,trust.trust().id(),"Subject",principal,now.plusSeconds(2));
        assertThat(replacement.link().id()).isNotEqualTo(link.link().id());
        assertThat(repository.findLink(tenant,link.link().id()).orElseThrow().lifecycleState()).isEqualTo("RETIRED");
        commands.retireTrust(tenant,trust.trust().id(),1,now.plusSeconds(3));
        assertThat(repository.activeLinkById(tenant,replacement.link().id())).isEmpty();
        assertThat(repository.findLink(tenant,replacement.link().id())).isPresent();
    }
}
