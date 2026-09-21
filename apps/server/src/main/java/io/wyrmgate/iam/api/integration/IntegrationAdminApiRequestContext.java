package io.wyrmgate.iam.api.integration;

import io.wyrmgate.iam.platform.id.IdGenerator;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

final class IntegrationAdminApiRequestContext {
    private static final String ATTRIBUTE =
            IntegrationAdminApiRequestContext.class.getName() + ".correlationId";

    private IntegrationAdminApiRequestContext() {}

    static UUID resolve(HttpServletRequest request, IdGenerator ids) {
        Object current = request.getAttribute(ATTRIBUTE);
        if (current instanceof UUID id) return id;
        String header = request.getHeader("X-Correlation-Id");
        if (header == null) {
            UUID generated = ids.nextId();
            request.setAttribute(ATTRIBUTE, generated);
            return generated;
        }
        try {
            UUID parsed = UUID.fromString(header);
            request.setAttribute(ATTRIBUTE, parsed);
            return parsed;
        } catch (IllegalArgumentException invalid) {
            UUID generated = ids.nextId();
            request.setAttribute(ATTRIBUTE, generated);
            throw IntegrationAdminApiException.validation(
                    generated, "X-Correlation-Id must be a UUID.");
        }
    }

    static UUID forError(HttpServletRequest request, IdGenerator ids) {
        Object current = request.getAttribute(ATTRIBUTE);
        if (current instanceof UUID id) return id;
        return resolveLenient(request, ids);
    }

    private static UUID resolveLenient(HttpServletRequest request, IdGenerator ids) {
        String header = request.getHeader("X-Correlation-Id");
        if (header != null) {
            try {
                UUID parsed = UUID.fromString(header);
                request.setAttribute(ATTRIBUTE, parsed);
                return parsed;
            } catch (IllegalArgumentException ignored) {}
        }
        UUID generated = ids.nextId();
        request.setAttribute(ATTRIBUTE, generated);
        return generated;
    }
}
