package io.wyrmgate.iam.api.security;

import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.idp.protocol.IdpAuthorizationController;
import io.wyrmgate.iam.idp.session.IdpBrowserSessionController;
import io.wyrmgate.iam.idp.session.IdpBrowserSessionService;
import io.wyrmgate.iam.idp.session.IdpCsrfTokenCodec;
import io.wyrmgate.iam.platform.id.IdGenerator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates same-origin control-plane requests from the first-party opaque browser session.
 * Tenant and governed actor are derived exclusively from server-side session state.
 */
public final class IdpBrowserSessionAuthenticationFilter extends OncePerRequestFilter {

    private final IdpBrowserSessionService sessions;
    private final IdpCsrfTokenCodec csrf;
    private final IdGenerator ids;

    public IdpBrowserSessionAuthenticationFilter(
            IdpBrowserSessionService sessions,
            IdpCsrfTokenCodec csrf,
            IdGenerator ids) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.csrf = Objects.requireNonNull(csrf, "csrf");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !(path.startsWith("/api/v1/")
                || path.equals("/api/auth/session")
                || path.equals("/api/auth/logout"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (hasBearer(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String rawSession = cookie(request, IdpAuthorizationController.BROWSER_SESSION_COOKIE);
        if (rawSession == null || rawSession.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        if (requiresCsrf(request)
                && !csrf.matches(
                        cookie(request, IdpBrowserSessionController.CSRF_COOKIE),
                        request.getHeader(IdpBrowserSessionController.CSRF_HEADER))) {
            SemanticAuthenticationFailureWriter.write(
                    response,
                    HttpServletResponse.SC_FORBIDDEN,
                    "csrf_failed",
                    "A valid same-origin CSRF proof is required for this request.",
                    ids);
            return;
        }

        try {
            var resolved = sessions.resolve(rawSession, Instant.now());
            if (resolved.isEmpty()) {
                filterChain.doFilter(request, response);
                return;
            }
            var session = resolved.orElseThrow();
            var authentication = new PreAuthenticatedAuthenticationToken(
                    session.context().principalId().toString(), null, List.of());
            SecurityContextHolder.getContext().setAuthentication(authentication);
            ControlPlaneActorRequestContext.set(
                    request,
                    new AuthenticatedAdministrativeActor(
                            session.tenant(), session.context().identityId()));
            filterChain.doFilter(request, response);
        } catch (RuntimeException unavailable) {
            SemanticAuthenticationFailureWriter.write(
                    response,
                    HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "authentication_context_unavailable",
                    "First-party authentication context is temporarily unavailable.",
                    ids);
        }
    }

    private static boolean hasBearer(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        return authorization != null
                && authorization.regionMatches(true, 0, "Bearer ", 0, 7);
    }

    private static boolean requiresCsrf(HttpServletRequest request) {
        String method = request.getMethod();
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)
                || "TRACE".equals(method)) {
            return false;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.startsWith("/api/v1/") || path.equals("/api/auth/logout");
    }

    private static String cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }
}
