package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.UUID;

public interface AccessAssignmentFactSink {

    String PROJECTION_INPUT_CHANGED = "access.assignment-projection-input-changed";

    void projectionInputChanged(TenantContext tenant, AccessAssignment assignment);

    default void projectionInputChanged(
            TenantContext tenant,
            AccessAssignment assignment,
            UUID correlationId,
            UUID causationId) {
        projectionInputChanged(tenant, assignment);
    }
}
