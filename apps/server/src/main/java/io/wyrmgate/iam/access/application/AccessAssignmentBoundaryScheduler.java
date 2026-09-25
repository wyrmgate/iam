package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;

public interface AccessAssignmentBoundaryScheduler {

    String HANDLER_TYPE = "access-effective-access-boundary";

    void scheduleBoundaries(
            TenantContext tenant,
            AccessAssignment assignment,
            Instant now);
}
