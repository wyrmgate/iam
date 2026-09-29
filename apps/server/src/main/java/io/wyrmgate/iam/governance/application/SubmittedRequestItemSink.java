package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.application.AccessRequestModels.RequestItem;
import io.wyrmgate.iam.platform.tenant.TenantContext;

/** Durable Governance publication boundary for submitted RequestItem evaluation. */
public interface SubmittedRequestItemSink {

    String REQUEST_ITEM_SUBMITTED =
            "governance.request-item-submitted";

    void submitted(TenantContext tenant, RequestItem item);

    void retryEvaluation(TenantContext tenant, RequestItem item);
}
