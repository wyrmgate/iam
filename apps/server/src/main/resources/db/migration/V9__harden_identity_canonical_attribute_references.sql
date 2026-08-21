-- Tighten same-tenant canonical-attribute references into same-semantic-tuple references.
-- These constraints prevent internally cross-wired provenance while preserving the accepted
-- Identity capability ownership and typed-value model established by V8.

ALTER TABLE identity.source_record
    ADD CONSTRAINT identity_source_record_source_ref_uq
    UNIQUE (tenant_id, id, source_system_id);

ALTER TABLE identity.attribute_definition_version
    ADD CONSTRAINT identity_attribute_definition_version_definition_ref_uq
    UNIQUE (tenant_id, id, attribute_definition_id);

ALTER TABLE identity.attribute_mapping_version
    ADD CONSTRAINT identity_attribute_mapping_version_provenance_ref_uq
    UNIQUE (tenant_id, id, source_system_id, attribute_definition_version_id);

ALTER TABLE identity.attribute_authority_rule_version
    ADD CONSTRAINT identity_attribute_authority_rule_definition_ref_uq
    UNIQUE (tenant_id, id, attribute_definition_version_id);

ALTER TABLE identity.canonical_attribute_candidate
    ADD CONSTRAINT identity_canonical_attribute_candidate_definition_ref_uq
    UNIQUE (tenant_id, id, attribute_definition_version_id),
    ADD CONSTRAINT identity_canonical_attribute_candidate_state_ref_uq
    UNIQUE (tenant_id, id, identity_id, attribute_definition_version_id),
    ADD CONSTRAINT identity_canonical_attribute_candidate_source_tuple_fk
    FOREIGN KEY (tenant_id, source_record_id, source_system_id)
    REFERENCES identity.source_record (tenant_id, id, source_system_id),
    ADD CONSTRAINT identity_canonical_attribute_candidate_mapping_tuple_fk
    FOREIGN KEY (tenant_id, mapping_version_id, source_system_id, attribute_definition_version_id)
    REFERENCES identity.attribute_mapping_version
        (tenant_id, id, source_system_id, attribute_definition_version_id);

ALTER TABLE identity.canonical_attribute_candidate_value
    ADD CONSTRAINT identity_candidate_value_candidate_definition_fk
    FOREIGN KEY (tenant_id, candidate_id, attribute_definition_version_id)
    REFERENCES identity.canonical_attribute_candidate
        (tenant_id, id, attribute_definition_version_id);

ALTER TABLE identity.canonical_attribute_state
    ADD CONSTRAINT identity_canonical_attribute_state_definition_ref_uq
    UNIQUE (tenant_id, id, attribute_definition_version_id),
    ADD CONSTRAINT identity_canonical_attribute_state_definition_tuple_fk
    FOREIGN KEY (tenant_id, attribute_definition_version_id, attribute_definition_id)
    REFERENCES identity.attribute_definition_version
        (tenant_id, id, attribute_definition_id),
    ADD CONSTRAINT identity_canonical_attribute_state_candidate_tuple_fk
    FOREIGN KEY (tenant_id, selected_candidate_id, identity_id, attribute_definition_version_id)
    REFERENCES identity.canonical_attribute_candidate
        (tenant_id, id, identity_id, attribute_definition_version_id),
    ADD CONSTRAINT identity_canonical_attribute_state_authority_tuple_fk
    FOREIGN KEY (tenant_id, authority_rule_version_id, attribute_definition_version_id)
    REFERENCES identity.attribute_authority_rule_version
        (tenant_id, id, attribute_definition_version_id);

ALTER TABLE identity.canonical_attribute_state_value
    ADD CONSTRAINT identity_state_value_state_definition_fk
    FOREIGN KEY (tenant_id, state_id, attribute_definition_version_id)
    REFERENCES identity.canonical_attribute_state
        (tenant_id, id, attribute_definition_version_id);

ALTER TABLE identity.canonical_attribute_override
    ADD CONSTRAINT identity_canonical_attribute_override_definition_ref_uq
    UNIQUE (tenant_id, id, attribute_definition_version_id),
    ADD CONSTRAINT identity_canonical_attribute_override_definition_tuple_fk
    FOREIGN KEY (tenant_id, attribute_definition_version_id, attribute_definition_id)
    REFERENCES identity.attribute_definition_version
        (tenant_id, id, attribute_definition_id);

ALTER TABLE identity.canonical_attribute_override_value
    ADD CONSTRAINT identity_override_value_override_definition_fk
    FOREIGN KEY (tenant_id, override_id, attribute_definition_version_id)
    REFERENCES identity.canonical_attribute_override
        (tenant_id, id, attribute_definition_version_id);

COMMENT ON CONSTRAINT identity_canonical_attribute_candidate_mapping_tuple_fk
    ON identity.canonical_attribute_candidate IS
    'Candidate provenance must use a mapping for the same SourceSystem and canonical definition version.';
COMMENT ON CONSTRAINT identity_canonical_attribute_state_candidate_tuple_fk
    ON identity.canonical_attribute_state IS
    'Selected candidate, when present, must belong to the same Identity and active canonical definition version as the resolved state.';
