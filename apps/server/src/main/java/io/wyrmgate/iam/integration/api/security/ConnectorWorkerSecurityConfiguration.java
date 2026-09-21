package io.wyrmgate.iam.integration.api.security;

import io.wyrmgate.iam.api.security.RequiredAudienceValidator;
import io.wyrmgate.iam.integration.application.WorkerRegistrationRepository;
import io.wyrmgate.iam.platform.id.IdGenerator;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(ConnectorWorkerAuthProperties.class)
public class ConnectorWorkerSecurityConfiguration {

    @Bean("connectorWorkerJwtDecoder")
    @ConditionalOnProperty(prefix = "iam.integration.worker-auth", name = "enabled", havingValue = "true")
    JwtDecoder connectorWorkerJwtDecoder(ConnectorWorkerAuthProperties properties) {
        String issuer = properties.requiredIssuerUri();
        String audience = properties.requiredAudience();
        return new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuer).build();
            OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(issuer),
                    new RequiredAudienceValidator(audience));
            decoder.setJwtValidator(validator);
            return decoder;
        });
    }

    @Bean
    @Order(1)
    @ConditionalOnProperty(prefix = "iam.integration.worker-auth", name = "enabled", havingValue = "true")
    SecurityFilterChain authenticatedConnectorWorkerSecurity(
            HttpSecurity http,
            @Qualifier("connectorWorkerJwtDecoder") JwtDecoder decoder,
            WorkerRegistrationRepository workers,
            IdGenerator ids) throws Exception {
        http.securityMatcher("/internal/connector-worker/v1/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(token ->
                                new JwtAuthenticationToken(token, List.<GrantedAuthority>of(), token.getSubject()))))
                .addFilterAfter(
                        new ConnectorWorkerResolutionFilter(workers, ids),
                        BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(1)
    @ConditionalOnProperty(
            prefix = "iam.integration.worker-auth",
            name = "enabled",
            havingValue = "false",
            matchIfMissing = true)
    SecurityFilterChain closedConnectorWorkerSecurity(HttpSecurity http) throws Exception {
        http.securityMatcher("/internal/connector-worker/v1/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().denyAll());
        return http.build();
    }
}
