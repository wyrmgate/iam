-- Governance issue state references normalized semantic evidence only.
ALTER TABLE governance.finding
 DROP CONSTRAINT governance_finding_provider_ck,
 DROP CONSTRAINT governance_finding_subject_kind_ck,
 DROP CONSTRAINT governance_finding_type_ck,
 ALTER COLUMN connector_binding_id DROP NOT NULL,
 ALTER COLUMN provider_stable_id DROP NOT NULL,
 ADD COLUMN identity_id uuid,
 ADD COLUMN principal_id uuid,
 ADD COLUMN entitlement_id uuid,
 ADD COLUMN application_target_id uuid,
 ADD COLUMN sod_rule_id uuid,
 ADD COLUMN related_binding_id uuid,
 ADD COLUMN related_principal_id uuid,
 ADD COLUMN related_entitlement_id uuid,
 ADD COLUMN exception_id uuid;
ALTER TABLE governance.finding ADD CONSTRAINT governance_finding_typed_context_ck CHECK (
 (finding_type IN ('UNMAPPED_PROVIDER_ENTITLEMENT','UNMAPPED_PROVIDER_GRANT_ENTITLEMENT','UNRESOLVED_PROVIDER_GRANT_PRINCIPAL')
  AND ((finding_type='UNMAPPED_PROVIDER_ENTITLEMENT' AND subject_kind='OBSERVED_ENTITLEMENT') OR
       (finding_type<>'UNMAPPED_PROVIDER_ENTITLEMENT' AND subject_kind='OBSERVED_GRANT'))
  AND connector_binding_id IS NOT NULL AND provider_stable_id IS NOT NULL AND btrim(provider_stable_id)<>''
  AND identity_id IS NULL AND principal_id IS NULL AND entitlement_id IS NULL AND application_target_id IS NULL
  AND sod_rule_id IS NULL AND related_binding_id IS NULL AND related_principal_id IS NULL AND related_entitlement_id IS NULL AND exception_id IS NULL)
 OR
 (finding_type='EXCESS_OBSERVED_GRANT' AND subject_kind='OBSERVED_GRANT'
  AND connector_binding_id IS NOT NULL AND provider_stable_id IS NOT NULL AND btrim(provider_stable_id)<>''
  AND principal_id IS NOT NULL AND entitlement_id IS NOT NULL AND application_target_id IS NOT NULL
  AND sod_rule_id IS NULL AND related_binding_id IS NULL AND related_principal_id IS NULL AND related_entitlement_id IS NULL AND exception_id IS NULL)
 OR
 (finding_type='OBSERVED_SOD_CONFLICT' AND subject_kind='OBSERVED_GRANT'
  AND connector_binding_id IS NOT NULL AND provider_stable_id IS NOT NULL AND btrim(provider_stable_id)<>''
  AND related_provider_stable_id IS NOT NULL AND btrim(related_provider_stable_id)<>''
  AND identity_id IS NOT NULL AND principal_id IS NOT NULL AND entitlement_id IS NOT NULL AND application_target_id IS NOT NULL
  AND sod_rule_id IS NOT NULL AND related_binding_id IS NOT NULL AND related_principal_id IS NOT NULL AND related_entitlement_id IS NOT NULL
  AND entitlement_id<>related_entitlement_id AND exception_id IS NULL)
 OR
 (finding_type='EXPIRED_EXCEPTION_EXPOSURE' AND subject_kind='GOVERNANCE_EXCEPTION'
  AND connector_binding_id IS NULL AND provider_stable_id IS NULL AND related_provider_stable_id IS NULL
  AND identity_id IS NOT NULL AND sod_rule_id IS NOT NULL AND exception_id IS NOT NULL
  AND principal_id IS NULL AND entitlement_id IS NULL AND application_target_id IS NULL
  AND related_binding_id IS NULL AND related_principal_id IS NULL AND related_entitlement_id IS NULL)
);
CREATE INDEX governance_finding_page_idx ON governance.finding(tenant_id,created_at,id);
CREATE INDEX governance_finding_state_page_idx ON governance.finding(tenant_id,lifecycle_state,created_at,id);
CREATE INDEX governance_exception_exposure_scan_idx ON governance.governance_exception(valid_until,tenant_id,id)
 WHERE lifecycle_state IN ('APPROVED','EXPIRED');
CREATE INDEX governance_observation_refresh_idx ON governance.observation_evaluation(updated_at,tenant_id,connector_binding_id) WHERE phase='COMPLETED';
CREATE INDEX integration_principal_mapping_witness_idx ON integration.principal_observation_mapping(tenant_id,principal_id,connector_binding_id,provider_stable_id)
 WHERE lifecycle_state='ACTIVE';
CREATE INDEX integration_entitlement_mapping_witness_idx ON integration.entitlement_observation_mapping(tenant_id,entitlement_id,connector_binding_id,provider_stable_id)
 WHERE lifecycle_state='ACTIVE';
CREATE INDEX integration_observed_grant_witness_idx ON integration.observed_grant(tenant_id,connector_binding_id,principal_provider_id,entitlement_provider_id,provider_stable_id)
 WHERE present;
