package io.wyrmgate.iam.idp.session;

import java.util.Optional;
import java.util.UUID;

/** Read-only, non-secret routing metadata required by the same-origin Console login form. */
public interface ConsoleLoginRouteQuery {

    Optional<Route> resolve();

    record Route(String clientId, UUID applicationTargetId) {
        public Route {
            if (clientId == null || clientId.isBlank()) {
                throw new IllegalArgumentException("clientId must not be blank");
            }
            if (applicationTargetId == null) {
                throw new IllegalArgumentException("applicationTargetId is required");
            }
        }
    }
}
