package io.wyrmgate.iam.integration.api.security;

import io.wyrmgate.iam.integration.application.WorkerRegistrationRepository;
import io.wyrmgate.iam.integration.domain.WorkerExternalSubject;
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

public final class ConnectorWorkerResolutionFilter extends OncePerRequestFilter {

    private final WorkerRegistrationRepository workers;
    private final IdGenerator ids;

    public ConnectorWorkerResolutionFilter(WorkerRegistrationRepository workers, IdGenerator ids) {
        this.workers = Objects.requireNonNull(workers, "workers");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !authentication.isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }
        try {
            WorkerExternalSubject subject = new WorkerExternalSubject(
                    jwt.getToken().getClaimAsString("iss"), jwt.getToken().getSubject());
            var worker = workers.findEnabledByExternalSubject(subject);
            if (worker.isEmpty()) {
                ConnectorWorkerAuthenticationFailureWriter.write(
                        response, HttpServletResponse.SC_UNAUTHORIZED,
                        "unresolved_worker",
                        "Authenticated subject is not bound to an enabled connector worker.",
                        ids);
                return;
            }
            ConnectorWorkerRequestContext.set(request, worker.orElseThrow());
            chain.doFilter(request, response);
        } catch (IllegalArgumentException invalid) {
            ConnectorWorkerAuthenticationFailureWriter.write(
                    response, HttpServletResponse.SC_UNAUTHORIZED,
                    "unresolved_worker",
                    "Authenticated subject is not bound to an enabled connector worker.",
                    ids);
        } catch (RuntimeException unavailable) {
            ConnectorWorkerAuthenticationFailureWriter.write(
                    response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "worker_authentication_context_unavailable",
                    "Connector-worker authentication context is temporarily unavailable.",
                    ids);
        }
    }
}
