package io.wyrmgate.iam.api.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import io.wyrmgate.iam.platform.crypto.SigningKeyMaterial;
import io.wyrmgate.iam.platform.crypto.SigningKeyProvider;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * First-party OAuth/OIDC protocol foundation.
 *
 * <p>This slice intentionally has no configured clients and no end-user login
 * mechanism. Discovery and public verification material are available when the
 * service is explicitly enabled, while authorization/token operations remain
 * fail closed until governed client registration and Principal authentication
 * are wired in later slices.</p>
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(IdpSsoProperties.class)
public class IdpSsoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "iam.sso", name = "enabled", havingValue = "true")
    AuthorizationServerSettings idpAuthorizationServerSettings(IdpSsoProperties properties) {
        return AuthorizationServerSettings.builder()
                .issuer(properties.requiredIssuer())
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "iam.sso", name = "enabled", havingValue = "true")
    JWKSource<SecurityContext> idpJwkSource(SigningKeyProvider signingKeys) {
        List<JWK> publicKeys = signingKeys.verificationKeys().stream()
                .map(IdpSsoConfiguration::publicJwk)
                .toList();
        if (publicKeys.isEmpty()) {
            throw new IllegalStateException("first-party SSO requires at least one public verification key");
        }
        JWKSet jwkSet = new JWKSet(publicKeys);
        return (selector, context) -> selector.select(jwkSet);
    }

    @Bean
    @ConditionalOnProperty(prefix = "iam.sso", name = "enabled", havingValue = "true")
    JwtDecoder idpJwtDecoder(JWKSource<SecurityContext> idpJwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(idpJwkSource);
    }

    @Bean
    @ConditionalOnProperty(prefix = "iam.sso", name = "enabled", havingValue = "true")
    RegisteredClientRepository idpRegisteredClientRepository() {
        return new ClosedRegisteredClientRepository();
    }

    @Bean
    @Order(0)
    @ConditionalOnProperty(prefix = "iam.sso", name = "enabled", havingValue = "true")
    SecurityFilterChain idpAuthorizationServerSecurity(
            HttpSecurity http,
            JWKSource<SecurityContext> idpJwkSource,
            AuthorizationServerSettings idpAuthorizationServerSettings,
            RegisteredClientRepository idpRegisteredClientRepository) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServer = new OAuth2AuthorizationServerConfigurer();
        RequestMatcher endpoints = authorizationServer.getEndpointsMatcher();

        http.securityMatcher(endpoints)
                .with(authorizationServer, server -> server.oidc(Customizer.withDefaults()))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .csrf(csrf -> csrf.ignoringRequestMatchers(endpoints));
        return http.build();
    }

    private static JWK publicJwk(SigningKeyMaterial material) {
        if (!(material.publicKey() instanceof RSAPublicKey rsaPublicKey)) {
            throw new IllegalStateException(
                    "first-party SSO currently requires RSA signing public keys; key " + material.keyId()
                            + " uses " + material.publicKey().getAlgorithm());
        }
        return new RSAKey.Builder(rsaPublicKey)
                .keyID(material.keyId())
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(toJwsAlgorithm(material.signingAlgorithm()))
                .build();
    }

    private static JWSAlgorithm toJwsAlgorithm(String signingAlgorithm) {
        return switch (signingAlgorithm) {
            case "SHA256withRSA" -> JWSAlgorithm.RS256;
            case "SHA384withRSA" -> JWSAlgorithm.RS384;
            case "SHA512withRSA" -> JWSAlgorithm.RS512;
            default -> throw new IllegalStateException(
                    "unsupported first-party SSO signing algorithm " + signingAlgorithm);
        };
    }

    /**
     * Explicit deny-by-absence client repository for the protocol-foundation
     * slice. It contains no demo/default registrations and cannot be mutated.
     */
    private static final class ClosedRegisteredClientRepository implements RegisteredClientRepository {
        @Override
        public void save(RegisteredClient registeredClient) {
            throw new UnsupportedOperationException(
                    "SSO client registration is not available until the governed Catalog registration slice is enabled");
        }

        @Override
        public RegisteredClient findById(String id) {
            return null;
        }

        @Override
        public RegisteredClient findByClientId(String clientId) {
            return null;
        }
    }
}
