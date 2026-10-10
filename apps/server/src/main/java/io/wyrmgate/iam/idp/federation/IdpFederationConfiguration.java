package io.wyrmgate.iam.idp.federation;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit wiring point for optional upstream federation adapters. */
@Configuration
@ConditionalOnProperty(prefix = "iam.idp", name = "enabled", havingValue = "true")
public class IdpFederationConfiguration {

    @Bean
    FederationAdapterRegistry federationAdapterRegistry(
            ObjectProvider<FederatedAuthenticationAdapter<?>> adapters) {
        return new FederationAdapterRegistry(adapters.orderedStream().toList());
    }
}
