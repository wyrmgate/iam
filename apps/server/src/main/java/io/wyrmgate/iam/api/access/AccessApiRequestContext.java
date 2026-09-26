package io.wyrmgate.iam.api.access;

import io.wyrmgate.iam.platform.id.IdGenerator;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.UUID;

final class AccessApiRequestContext {

    private static final String ATTRIBUTE =
            AccessApiRequestContext.class.getName() + ".correlationId";

    private AccessApiRequestContext() {}

    static UUID resolveCorrelationId(
            HttpServletRequest request, IdGenerator ids) {
        Objects.requireNonNull(request, "request");
        Object current = request.getAttribute(ATTRIBUTE);
        if (current instanceof UUID id) return id;
        String header = request.getHeader("X-Correlation-Id");
        if (header == null) {
            UUID id = ids.nextId();
            request.setAttribute(ATTRIBUTE, id);
            return id;
        }
        try {
            UUID id = UUID.fromString(header);
            request.setAttribute(ATTRIBUTE, id);
            return id;
        } catch (IllegalArgumentException invalid) {
            UUID id = ids.nextId();
            request.setAttribute(ATTRIBUTE, id);
            throw AccessApiException.validation(
                    id,
                    "X-Correlation-Id",
                    "invalid_uuid",
                    "X-Correlation-Id must be a UUID.");
        }
    }

    static UUID correlationIdForError(
            HttpServletRequest request, IdGenerator ids) {
        Object current = request.getAttribute(ATTRIBUTE);
        if (current instanceof UUID id) return id;
        try {
            String header = request.getHeader("X-Correlation-Id");
            if (header != null) {
                UUID id = UUID.fromString(header);
                request.setAttribute(ATTRIBUTE, id);
                return id;
            }
        } catch (IllegalArgumentException ignored) {
        }
        UUID id = ids.nextId();
        request.setAttribute(ATTRIBUTE, id);
        return id;
    }
}
