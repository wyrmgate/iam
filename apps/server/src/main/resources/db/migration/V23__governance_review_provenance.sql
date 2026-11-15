-- Preserve Access-owned assignment provenance in immutable review evidence.
ALTER TABLE governance.review_item
    DROP CONSTRAINT governance_review_item_provenance_ck;
ALTER TABLE governance.review_item
    ADD CONSTRAINT governance_review_item_provenance_ck CHECK (
        (provenance_kind = 'MANUAL' AND provenance_ref_id IS NULL)
        OR (provenance_kind IN ('REQUEST_ITEM', 'LIFECYCLE_POLICY_RULE') AND provenance_ref_id IS NOT NULL)
    );
