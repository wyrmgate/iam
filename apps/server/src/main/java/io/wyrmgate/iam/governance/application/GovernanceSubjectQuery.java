package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.domain.GovernanceExceptionModels.GovernanceException;
import io.wyrmgate.iam.governance.domain.ReviewModels.ReviewCampaign;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Governance-owned bounded query used by subject investigation surfaces. */
public interface GovernanceSubjectQuery {

    record Position(Instant createdAt, UUID id) {}

    record CampaignPage(List<ReviewCampaign> items, Position nextPosition) {
        public CampaignPage { items = List.copyOf(items); }
    }

    record ExceptionPage(List<GovernanceException> items, Position nextPosition) {
        public ExceptionPage { items = List.copyOf(items); }
    }

    CampaignPage campaigns(
            TenantContext tenant,
            UUID subjectIdentityId,
            Position after,
            int limit);

    ExceptionPage exceptions(
            TenantContext tenant,
            UUID subjectIdentityId,
            Position after,
            int limit);
}
