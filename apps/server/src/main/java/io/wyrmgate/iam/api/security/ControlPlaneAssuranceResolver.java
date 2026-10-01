package io.wyrmgate.iam.api.security;

import io.wyrmgate.iam.administration.domain.AuthenticationAssuranceContext;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Transport-adapter boundary that maps already validated provider authentication context
 * into canonical Wyrmgate assurance semantics.
 */
@FunctionalInterface
public interface ControlPlaneAssuranceResolver {

    AuthenticationAssuranceContext resolve(Jwt validatedJwt);
}
