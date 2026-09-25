package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.platform.tenant.TenantContext;

public interface AccessAssignmentFactSink {

    String PROJECTION_INPUT_CHANGED = "access.assignment-projection-input-changed";

    void projectionInputChanged(TenantContext tenant, AccessAssignment assignment);
}
