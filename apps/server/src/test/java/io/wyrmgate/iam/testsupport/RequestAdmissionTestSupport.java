package io.wyrmgate.iam.testsupport;

import io.wyrmgate.iam.governance.application.RequestAdmissionContext;
import java.util.UUID;

/** Explicit synthetic evaluator context for tests not exercising Catalog/Identity admission. */
public final class RequestAdmissionTestSupport {
    private RequestAdmissionTestSupport() {}
    public static RequestAdmissionContext context() {
        return new RequestAdmissionContext(1, new UUID(0, 1),
                "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64), new UUID(0, 2));
    }
}
