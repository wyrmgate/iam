CREATE INDEX governance_review_campaign_api_page_idx
    ON governance.review_campaign (
        tenant_id, created_at, id);

CREATE INDEX governance_review_item_campaign_api_page_idx
    ON governance.review_item (
        tenant_id, review_campaign_id, created_at, id);

CREATE INDEX governance_review_remediation_item_idx
    ON governance.review_remediation (
        tenant_id, review_item_id);
