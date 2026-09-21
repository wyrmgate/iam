package io.wyrmgate.iam.integration.api.security;

import io.wyrmgate.iam.platform.id.IdGenerator;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

final class ConnectorWorkerAuthenticationFailureWriter {
    private ConnectorWorkerAuthenticationFailureWriter() {}

    static void write(
            HttpServletResponse response,
            int status,
            String code,
            String message,
            IdGenerator ids) throws IOException {
        UUID correlationId = ids.nextId();
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Correlation-Id", correlationId.toString());
        if (status == HttpServletResponse.SC_UNAUTHORIZED) response.setHeader("WWW-Authenticate", "Bearer");
        response.getWriter().write("{\"code\":\"" + code
                + "\",\"message\":\"" + message
                + "\",\"correlationId\":\"" + correlationId + "\"}");
    }
}
