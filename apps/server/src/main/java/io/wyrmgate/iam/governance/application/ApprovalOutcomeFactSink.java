package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.domain.ApprovalCase;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.UUID;

public interface ApprovalOutcomeFactSink {

    String REQUEST_ITEM_OUTCOME =
            "governance.approval-outcome.request-item";

    void terminalOutcome(
            TenantContext tenant,
            ApprovalCase approvalCase,
            UUID correlationId,
            UUID causationId);
}
