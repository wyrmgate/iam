package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.domain.ReviewModels.ReviewCampaign;
import io.wyrmgate.iam.governance.domain.ReviewModels.ReviewRemediation;
import io.wyrmgate.iam.platform.tenant.TenantContext;

public interface ReviewWorkSink {
    String GENERATION_REQUESTED =
            "governance.review-generation-requested";
    String REMEDIATION_REQUESTED =
            "governance.review-remediation-requested";

    void generationRequested(
            TenantContext tenant,
            ReviewCampaign campaign);

    void remediationRequested(
            TenantContext tenant,
            ReviewRemediation remediation);
}
