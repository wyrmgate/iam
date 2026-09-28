package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.domain.AccessRequest;
import io.wyrmgate.iam.governance.domain.RequestItem;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccessRequestRepository {

    void insertRequest(
            TenantContext tenant, AccessRequest request);

    void insertItem(
            TenantContext tenant, RequestItem item);

    Optional<AccessRequest> findRequest(
            TenantContext tenant, UUID requestId);

    Optional<RequestItem> findItem(
            TenantContext tenant, UUID itemId);

    List<RequestItem> findItems(
            TenantContext tenant, UUID requestId);

    AccessRequest updateRequestState(
            TenantContext tenant,
            UUID requestId,
            AccessRequest.LifecycleState state,
            long expectedRevision,
            Instant now,
            Instant submittedAt,
            Instant completedAt);

    RequestItem updateItemState(
            TenantContext tenant,
            UUID itemId,
            RequestItem.LifecycleState state,
            UUID approvalPlanId,
            UUID accessAssignmentId,
            String denialCode,
            long expectedRevision,
            Instant now);
}
