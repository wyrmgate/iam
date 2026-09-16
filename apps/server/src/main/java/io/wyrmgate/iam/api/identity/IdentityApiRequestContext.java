package io.wyrmgate.iam.api.identity;

import io.wyrmgate.iam.platform.id.IdGenerator;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.UUID;

final class IdentityApiRequestContext {

    private static final String ATTRIBUTE = IdentityApiRequestContext.class.getName() + ".correlationId";

    private IdentityApiRequestContext() {
    }

    static UUID resolveCorrelationId(HttpServletRequest request, IdGenerator ids) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(ids, "ids");
        Object current = request.getAttribute(ATTRIBUTE);
        if (current instanceof UUID id) {
            return id;
        }
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
            throw IdentityApiException.validation(
                    generated,
                    "X-Correlation-Id",
                    "invalid_uuid",
                    "X-Correlation-Id must be a UUID.");
        }
    }

    static UUID correlationIdForError(HttpServletRequest request, IdGenerator ids) {
        Object current = request.getAttribute(ATTRIBUTE);
        if (current instanceof UUID id) {
            return id;
        }
        String header = request.getHeader("X-Correlation-Id");
        if (header != null) {
            try {
                UUID parsed = UUID.fromString(header);
                request.setAttribute(ATTRIBUTE, parsed);
                return parsed;
            } catch (IllegalArgumentException ignored) {
                // A malformed caller value never becomes the public correlation identifier.
            }
        }
        UUID generated = ids.nextId();
        request.setAttribute(ATTRIBUTE, generated);
        return generated;
    }
}
