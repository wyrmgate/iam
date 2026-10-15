ALTER TABLE governance.request_item
    ADD COLUMN principal_constraint_kind varchar(16) NULL,
    ADD COLUMN specific_principal_id uuid NULL,
    ADD COLUMN valid_from timestamptz NULL,
    ADD COLUMN valid_until timestamptz NULL,
    ADD COLUMN access_assignment_id uuid NULL;

-- V26 request rows predate explicit principal semantics. Preserve their prior
-- unconstrained meaning as an explicit migration decision, not a runtime default.
UPDATE governance.request_item
SET principal_constraint_kind = 'ANY'
WHERE principal_constraint_kind IS NULL;

ALTER TABLE governance.request_item
    ALTER COLUMN principal_constraint_kind SET NOT NULL,
    ADD CONSTRAINT request_item_principal_constraint_kind_ck
        CHECK (principal_constraint_kind IN ('ANY', 'SPECIFIC')),
    ADD CONSTRAINT request_item_principal_constraint_shape_ck CHECK (
        (principal_constraint_kind = 'SPECIFIC'
            AND specific_principal_id IS NOT NULL)
        OR
        (principal_constraint_kind = 'ANY'
            AND specific_principal_id IS NULL)
    ),
    ADD CONSTRAINT request_item_validity_ck
        CHECK (
            valid_until IS NULL
            OR valid_from IS NULL
            OR valid_until > valid_from),
    ADD CONSTRAINT request_item_access_assignment_shape_ck CHECK (
        (state = 'APPLIED' AND access_assignment_id IS NOT NULL)
        OR
        (state <> 'APPLIED' AND access_assignment_id IS NULL)
    );

CREATE INDEX request_item_access_assignment_idx
    ON governance.request_item (tenant_id, access_assignment_id)
    WHERE access_assignment_id IS NOT NULL;

COMMENT ON COLUMN governance.request_item.access_assignment_id IS
    'Stable Access-owned AccessAssignment ID recorded after application. Intentionally no cross-capability foreign key.';

ALTER TABLE access.access_assignment
    DROP CONSTRAINT access_assignment_provenance_kind_ck,
    DROP CONSTRAINT access_assignment_manual_provenance_ck,
    ADD CONSTRAINT access_assignment_provenance_kind_ck
        CHECK (provenance_kind IN ('MANUAL', 'REQUEST_ITEM')),
    ADD CONSTRAINT access_assignment_provenance_shape_ck CHECK (
        (provenance_kind = 'MANUAL' AND provenance_ref_id IS NULL)
        OR
        (provenance_kind = 'REQUEST_ITEM' AND provenance_ref_id IS NOT NULL)
    );

CREATE UNIQUE INDEX access_assignment_request_item_provenance_uq
    ON access.access_assignment (tenant_id, provenance_ref_id)
    WHERE provenance_kind = 'REQUEST_ITEM';

COMMENT ON COLUMN access.access_assignment.provenance_ref_id IS
    'Stable causal reference within the provenance kind. REQUEST_ITEM stores the originating Governance RequestItem ID without a cross-capability foreign key.';
