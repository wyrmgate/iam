package io.wyrmgate.iam.api.security;

import io.wyrmgate.iam.platform.id.IdGenerator;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

final class SemanticAuthenticationFailureWriter {

    private SemanticAuthenticationFailureWriter() {
    }

    static void write(
            HttpServletResponse response,
            int status,
            String code,
            String message,
            IdGenerator idGenerator) throws IOException {
        Objects.requireNonNull(response, "response");
        UUID correlationId = Objects.requireNonNull(idGenerator, "idGenerator").nextId();
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Correlation-Id", correlationId.toString());
        if (status == HttpServletResponse.SC_UNAUTHORIZED) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        response.getWriter().write("{\"code\":\"" + code
                + "\",\"message\":\"" + message
                + "\",\"correlationId\":\"" + correlationId + "\"}");
    }
}
