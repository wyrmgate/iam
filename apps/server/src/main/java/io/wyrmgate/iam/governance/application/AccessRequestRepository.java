package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.application.AccessRequestModels.AccessRequest;
import io.wyrmgate.iam.governance.application.AccessRequestModels.ItemState;
import io.wyrmgate.iam.governance.application.AccessRequestModels.RequestItem;
import io.wyrmgate.iam.governance.application.AccessRequestModels.RequestState;
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
            TenantContext tenant, UUID itemId);

    List<RequestItem> findItems(
            TenantContext tenant, UUID requestId);

    AccessRequest updateRequestState(
            TenantContext tenant,
            UUID requestId,
            RequestState state,
            long expectedRevision,
            Instant now,
            Instant submittedAt);

    RequestItem updateItemState(
            TenantContext tenant,
            UUID itemId,
            ItemState state,
            UUID approvalCaseId,
            String evaluationCode,
            long expectedRevision,
            Instant now);
}
