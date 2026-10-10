package io.wyrmgate.iam.api.security;

import io.wyrmgate.iam.administration.application.ControlPlaneActorResolver;
import io.wyrmgate.iam.administration.domain.AuthenticationAssuranceContext;
import io.wyrmgate.iam.idp.session.IdpBrowserSessionService;
import io.wyrmgate.iam.idp.session.IdpCsrfTokenCodec;
import io.wyrmgate.iam.platform.id.IdGenerator;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/** Provider-neutral authentication boundary for Wyrmgate control-plane APIs. */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(ControlPlaneAuthProperties.class)
public class ControlPlaneSecurityConfiguration {

    @Bean
    SemanticAuthenticationEntryPoint semanticAuthenticationEntryPoint(IdGenerator idGenerator) {
        return new SemanticAuthenticationEntryPoint(idGenerator);
    }

    @Bean
    @ConditionalOnMissingBean(ControlPlaneAssuranceResolver.class)
    ControlPlaneAssuranceResolver baselineControlPlaneAssuranceResolver() {
        return validatedJwt -> AuthenticationAssuranceContext.baseline();
    }

    @Bean
    @ConditionalOnProperty(prefix = "iam.auth", name = "enabled", havingValue = "true")
    JwtDecoder controlPlaneJwtDecoder(ControlPlaneAuthProperties properties) {
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

    /**
     * When first-party IdP is enabled, same-origin opaque browser sessions and the optional
     * external bearer mode share one provider-neutral control-plane boundary. Explicit bearer
     * authentication takes precedence when a bearer header is supplied.
     */
    @Bean
    @ConditionalOnProperty(prefix = "iam.idp", name = "enabled", havingValue = "true")
    @Order(1)
    SecurityFilterChain firstPartyControlPlaneSecurity(
            HttpSecurity http,
            @Qualifier("controlPlaneJwtDecoder") ObjectProvider<JwtDecoder> controlPlaneJwtDecoders,
            IdpBrowserSessionService sessions,
            IdpCsrfTokenCodec csrf,
            ControlPlaneActorResolver actorResolver,
            ControlPlaneAssuranceResolver assuranceResolver,
            IdGenerator idGenerator,
            SemanticAuthenticationEntryPoint entryPoint) throws Exception {
        http.securityMatcher("/api/auth/**", "/api/v1/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/auth/login", "/api/auth/session", "/api/auth/logout").permitAll()
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint));

        IdpBrowserSessionAuthenticationFilter browserFilter =
                new IdpBrowserSessionAuthenticationFilter(sessions, csrf, idGenerator);
        JwtDecoder decoder = controlPlaneJwtDecoders.getIfAvailable();
        if (decoder != null) {
            http.oauth2ResourceServer(resourceServer -> resourceServer
                            .authenticationEntryPoint(entryPoint)
                            .jwt(jwt -> jwt
                                    .decoder(decoder)
                                    .jwtAuthenticationConverter(token -> new JwtAuthenticationToken(
                                            token,
                                            List.<GrantedAuthority>of(),
                                            token.getSubject()))))
                    .addFilterBefore(browserFilter, BearerTokenAuthenticationFilter.class)
                    .addFilterAfter(
                            new ControlPlaneActorResolutionFilter(
                                    actorResolver, assuranceResolver, idGenerator),
                            BearerTokenAuthenticationFilter.class);
        } else {
            http.addFilterBefore(browserFilter, AuthorizationFilter.class);
        }
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "iam.auth", name = "enabled", havingValue = "true")
    @Order(2)
    SecurityFilterChain authenticatedControlPlaneSecurity(
            HttpSecurity http,
            @Qualifier("controlPlaneJwtDecoder") JwtDecoder controlPlaneJwtDecoder,
            ControlPlaneActorResolver actorResolver,
            ControlPlaneAssuranceResolver assuranceResolver,
            IdGenerator idGenerator,
            SemanticAuthenticationEntryPoint entryPoint) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/api/system/info").permitAll()
                        .requestMatchers("/api/openapi/**", "/api/docs/**", "/swagger-ui/**").permitAll()
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint(entryPoint)
                        .jwt(jwt -> jwt
                                .decoder(controlPlaneJwtDecoder)
                                .jwtAuthenticationConverter(token -> new JwtAuthenticationToken(
                                        token,
                                        List.<GrantedAuthority>of(),
                                        token.getSubject()))))
                .addFilterAfter(
                        new ControlPlaneActorResolutionFilter(
                                actorResolver, assuranceResolver, idGenerator),
                        BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "iam.auth",
            name = "enabled",
            havingValue = "false",
            matchIfMissing = true)
    @Order(2)
    SecurityFilterChain closedControlPlaneSecurity(
            HttpSecurity http,
            SemanticAuthenticationEntryPoint entryPoint) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/api/system/info").permitAll()
                        .requestMatchers("/api/openapi/**", "/api/docs/**", "/swagger-ui/**").permitAll()
                        .requestMatchers("/api/v1/**").denyAll()
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint));
        return http.build();
    }
}
