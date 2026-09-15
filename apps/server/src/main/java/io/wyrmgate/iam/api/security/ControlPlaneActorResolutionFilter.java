package io.wyrmgate.iam.api.security;

import io.wyrmgate.iam.administration.application.ControlPlaneActorResolver;
import io.wyrmgate.iam.administration.domain.ExternalAuthenticationSubject;
import io.wyrmgate.iam.platform.id.IdGenerator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/** Resolves validated JWT issuer+subject into the server-side tenant/governed-Identity actor binding. */
public final class ControlPlaneActorResolutionFilter extends OncePerRequestFilter {

    private final ControlPlaneActorResolver actorResolver;
    private final IdGenerator idGenerator;

    public ControlPlaneActorResolutionFilter(ControlPlaneActorResolver actorResolver, IdGenerator idGenerator) {
        this.actorResolver = Objects.requireNonNull(actorResolver, "actorResolver");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)
                || !authentication.isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        String issuer = jwtAuthentication.getToken().getClaimAsString("iss");
        String subject = jwtAuthentication.getToken().getSubject();
        try {
            var resolved = actorResolver.resolve(new ExternalAuthenticationSubject(issuer, subject));
            if (resolved.isEmpty()) {
                SemanticAuthenticationFailureWriter.write(
                        response,
                        HttpServletResponse.SC_UNAUTHORIZED,
                        "unresolved_actor",
                        "Authenticated subject is not bound to a Wyrmgate control-plane actor.",
                        idGenerator);
                return;
            }
            ControlPlaneActorRequestContext.set(request, resolved.orElseThrow());
            filterChain.doFilter(request, response);
        } catch (IllegalArgumentException invalidSubject) {
            SemanticAuthenticationFailureWriter.write(
                    response,
                    HttpServletResponse.SC_UNAUTHORIZED,
                    "unresolved_actor",
                    "Authenticated subject is not bound to a Wyrmgate control-plane actor.",
                    idGenerator);
        } catch (RuntimeException unavailable) {
            SemanticAuthenticationFailureWriter.write(
                    response,
                    HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "authentication_context_unavailable",
                    "Control-plane actor resolution is temporarily unavailable.",
                    idGenerator);
        }
    }
}
