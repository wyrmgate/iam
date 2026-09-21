package io.wyrmgate.iam.integration.api.security;

import io.wyrmgate.iam.integration.domain.WorkerRegistration;
import jakarta.servlet.http.HttpServletRequest;

public final class ConnectorWorkerRequestContext {
    private static final String ATTRIBUTE = ConnectorWorkerRequestContext.class.getName() + ".worker";

    private ConnectorWorkerRequestContext() {}

    public static void set(HttpServletRequest request, WorkerRegistration worker) {
        request.setAttribute(ATTRIBUTE, worker);
    }

    public static WorkerRegistration require(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        if (value instanceof WorkerRegistration worker) return worker;
        throw new IllegalStateException("trusted connector-worker context is unavailable");
    }
}
