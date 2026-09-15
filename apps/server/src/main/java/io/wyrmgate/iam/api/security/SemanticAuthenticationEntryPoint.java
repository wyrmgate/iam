package io.wyrmgate.iam.api.security;

import io.wyrmgate.iam.platform.id.IdGenerator;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/** Stable public 401 response that never exposes token/framework validation details. */
public final class SemanticAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final IdGenerator idGenerator;

    public SemanticAuthenticationEntryPoint(IdGenerator idGenerator) {
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException) throws IOException, ServletException {
        SemanticAuthenticationFailureWriter.write(
                response,
                HttpServletResponse.SC_UNAUTHORIZED,
                "authentication_required",
                "Valid bearer authentication is required.",
                idGenerator);
    }
}
