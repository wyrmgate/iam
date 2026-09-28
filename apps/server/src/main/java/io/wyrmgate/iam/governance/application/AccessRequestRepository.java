package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.domain.AccessRequest;
import io.wyrmgate.iam.governance.domain.RequestItem;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccessRequestRepository {

    void insertRequest(TenantContext tenant, AccessRequest request);

    void insertItem(TenantContext tenant, RequestItem item);

    Optional<AccessRequest> findRequest(
            TenantContext tenant, UUID requestId);

    Optional<RequestItem> findItem(
            TenantContext tenant, UUID requestItemId);

    List<RequestItem> findItems(
            TenantContext tenant, UUID requestId);

    Optional<RequestItem> findItemByApprovalCase(
            TenantContext tenant, UUID approvalCaseId);

    AccessRequest updateRequestState(
            TenantContext tenant,
            UUID requestId,
            long expectedRevision,
            AccessRequest.LifecycleState state,
            Instant now,
            Instant completedAt);

    RequestItem updateItemState(
            TenantContext tenant,
            UUID requestItemId,
            long expectedRevision,
            RequestItem.LifecycleState state,
            UUID approvalCaseId,
            UUID accessAssignmentId,
            Instant now,
            Instant completedAt);
}
