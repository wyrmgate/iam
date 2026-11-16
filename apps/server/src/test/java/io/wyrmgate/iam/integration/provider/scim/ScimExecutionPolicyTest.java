package io.wyrmgate.iam.integration.provider.scim;

import static org.assertj.core.api.Assertions.*;
import io.wyrmgate.iam.integration.application.ConnectorExecutionRepository.ExecutionConfiguration;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScimExecutionPolicyTest {
    @Test void exactTenantConnectorEndpointAndSecretAreRequired(){
        UUID tenant=UUID.randomUUID(),connector=UUID.randomUUID();
        var policy=ScimExecutionPolicy.configured(tenant+"|"+connector+"|https://scim.example/v2|env:IAM_CONNECTOR_TEST");
        var good=config(connector,"https://scim.example/v2","env:IAM_CONNECTOR_TEST");
        assertThat(policy.permits(new TenantContext(tenant),good)).isTrue();
        assertThat(policy.permits(new TenantContext(UUID.randomUUID()),good)).isFalse();
        assertThat(policy.permits(new TenantContext(tenant),config(UUID.randomUUID(),"https://scim.example/v2","env:IAM_CONNECTOR_TEST"))).isFalse();
        assertThat(policy.permits(new TenantContext(tenant),config(connector,"https://other.example/v2","env:IAM_CONNECTOR_TEST"))).isFalse();
        assertThat(policy.permits(new TenantContext(tenant),config(connector,"https://scim.example/v2","env:IAM_DB_PASSWORD"))).isFalse();
        assertThat(ScimExecutionPolicy.configured("").permits(new TenantContext(tenant),good)).isFalse();
        assertThatThrownBy(()->ScimExecutionPolicy.configured(tenant+"|"+connector+"|http://scim.example/v2|env:IAM_CONNECTOR_TEST")).isInstanceOf(IllegalArgumentException.class);
    }
    private ExecutionConfiguration config(UUID id,String uri,String ref){
        return new ExecutionConfiguration(UUID.randomUUID(),id,"SCIM_2","scim-2","1.0",1,Map.of("baseUri",uri),ref,"scim-2.principal",1,true,false,false,1,1);
    }
}
