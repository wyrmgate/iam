package io.wyrmgate.iam.api.security;

import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;

/** Request-scoped transport holder for the trusted actor resolved from a validated bearer token. */
public final class ControlPlaneActorRequestContext {

    static final String ATTRIBUTE = ControlPlaneActorRequestContext.class.getName() + ".actor";

    private ControlPlaneActorRequestContext() {
    }

    static void set(HttpServletRequest request, AuthenticatedAdministrativeActor actor) {
        request.setAttribute(ATTRIBUTE, Objects.requireNonNull(actor, "actor"));
    }

    public static AuthenticatedAdministrativeActor require(HttpServletRequest request) {
        Object actor = request.getAttribute(ATTRIBUTE);
        if (actor instanceof AuthenticatedAdministrativeActor authenticatedActor) {
            return authenticatedActor;
        }
        throw new IllegalStateException("trusted control-plane actor has not been resolved for this request");
    }
}
