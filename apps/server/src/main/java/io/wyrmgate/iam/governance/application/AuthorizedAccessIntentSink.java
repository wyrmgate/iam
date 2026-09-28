package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.application.AccessRequestModels.RequestItem;
import io.wyrmgate.iam.platform.tenant.TenantContext;

/**
 * Durable Governance publication boundary for an authorized RequestItem.
 *
 * <p>The sink is invoked in the same transaction that records AUTHORIZED.
 * Consumers must re-read Governance state before applying the intent.</p>
 */
public interface AuthorizedAccessIntentSink {

    String REQUEST_ITEM_AUTHORIZED =
            "governance.request-item-authorized";

    void authorized(
            TenantContext tenant,
            RequestItem item);
}
