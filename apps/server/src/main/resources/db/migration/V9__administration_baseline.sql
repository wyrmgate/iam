-- Administration capability current-state persistence baseline.
-- Generated once from the verified pre-rebaseline schema, then maintained as reviewed source.

CREATE TABLE administration.administrative_break_glass_obligation (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    break_glass_operation_id uuid CONSTRAINT administrative_break_glass_ob_break_glass_operation_id_not_null NOT NULL,
    obligation_type character varying(32) NOT NULL,
    state character varying(24) NOT NULL,
    completed_at timestamp with time zone,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT administrative_break_glass_obligation_revision_ck CHECK ((revision > 0)),
    CONSTRAINT administrative_break_glass_obligation_shape_ck CHECK (((((state)::text = ANY ((ARRAY['PENDING'::character varying, 'MANUAL_REQUIRED'::character varying])::text[])) AND (completed_at IS NULL)) OR (((state)::text = 'COMPLETED'::text) AND (completed_at IS NOT NULL)))),
    CONSTRAINT administrative_break_glass_obligation_state_ck CHECK (((state)::text = ANY ((ARRAY['PENDING'::character varying, 'COMPLETED'::character varying, 'MANUAL_REQUIRED'::character varying])::text[]))),
    CONSTRAINT administrative_break_glass_obligation_timestamp_ck CHECK ((updated_at >= created_at)),
    CONSTRAINT administrative_break_glass_obligation_type_ck CHECK (((obligation_type)::text = ANY ((ARRAY['SECURITY_NOTIFICATION'::character varying, 'POST_USE_REVIEW'::character varying])::text[])))
);

COMMENT ON TABLE administration.administrative_break_glass_obligation IS 'Durable SECURITY_NOTIFICATION and POST_USE_REVIEW work/evidence obligations. Obligation state never extends emergency authority.';

CREATE TABLE administration.administrative_break_glass_operation (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    actor_identity_id uuid NOT NULL,
    role_id uuid NOT NULL,
    scope_type character varying(48) NOT NULL,
    scope_resource_type character varying(128),
    scope_ref_id uuid,
    scope_key character varying(64),
    reason character varying(2048) NOT NULL,
    incident_reference character varying(512) CONSTRAINT administrative_break_glass_operatio_incident_reference_not_null NOT NULL,
    valid_from timestamp with time zone NOT NULL,
    valid_until timestamp with time zone NOT NULL,
    activation_assurance_level character varying(16) CONSTRAINT administrative_break_glass__activation_assurance_level_not_null NOT NULL,
    activation_authenticated_at timestamp with time zone,
    activation_step_up_at timestamp with time zone CONSTRAINT administrative_break_glass_opera_activation_step_up_at_not_null NOT NULL,
    max_assurance_age_seconds bigint CONSTRAINT administrative_break_glass_o_max_assurance_age_seconds_not_null NOT NULL,
    state character varying(24) NOT NULL,
    activated_at timestamp with time zone NOT NULL,
    revoked_by_identity_id uuid,
    revoked_at timestamp with time zone,
    correlation_id uuid,
    causation_id uuid,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT administrative_break_glass_assurance_ck CHECK ((((activation_assurance_level)::text = 'STRONG'::text) AND (max_assurance_age_seconds > 0))),
    CONSTRAINT administrative_break_glass_incident_ck CHECK ((btrim((incident_reference)::text) <> ''::text)),
    CONSTRAINT administrative_break_glass_reason_ck CHECK ((btrim((reason)::text) <> ''::text)),
    CONSTRAINT administrative_break_glass_revision_ck CHECK ((revision > 0)),
    CONSTRAINT administrative_break_glass_revocation_shape_ck CHECK (((((state)::text = 'ACTIVE'::text) AND (revoked_by_identity_id IS NULL) AND (revoked_at IS NULL)) OR (((state)::text = 'REVOKED'::text) AND (revoked_by_identity_id IS NOT NULL) AND (revoked_at IS NOT NULL)))),
    CONSTRAINT administrative_break_glass_scope_shape_ck CHECK (((((scope_type)::text = 'GLOBAL'::text) AND (scope_resource_type IS NULL) AND (scope_ref_id IS NULL) AND (scope_key IS NULL)) OR (((scope_type)::text = 'SPECIFIC_RESOURCE'::text) AND (scope_resource_type IS NOT NULL) AND (btrim((scope_resource_type)::text) <> ''::text) AND (scope_ref_id IS NOT NULL) AND (scope_key IS NULL)) OR (((scope_type)::text = 'CANONICAL_ATTRIBUTE_CLASSIFICATION'::text) AND (scope_resource_type IS NULL) AND (scope_ref_id IS NULL) AND (scope_key IS NOT NULL) AND (btrim((scope_key)::text) <> ''::text)) OR (((scope_type)::text <> ALL ((ARRAY['GLOBAL'::character varying, 'SPECIFIC_RESOURCE'::character varying, 'CANONICAL_ATTRIBUTE_CLASSIFICATION'::character varying])::text[])) AND (scope_resource_type IS NULL) AND (scope_ref_id IS NOT NULL) AND (scope_key IS NULL)))),
    CONSTRAINT administrative_break_glass_scope_type_ck CHECK (((scope_type)::text = ANY ((ARRAY['GLOBAL'::character varying, 'ORGANIZATION'::character varying, 'APPLICATION'::character varying, 'APPLICATION_TARGET'::character varying, 'SOURCE_SYSTEM'::character varying, 'CONNECTOR_INSTANCE'::character varying, 'IDENTITY_POPULATION'::character varying, 'CANONICAL_ATTRIBUTE_CLASSIFICATION'::character varying, 'SPECIFIC_RESOURCE'::character varying])::text[]))),
    CONSTRAINT administrative_break_glass_state_ck CHECK (((state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'REVOKED'::character varying])::text[]))),
    CONSTRAINT administrative_break_glass_timestamp_ck CHECK ((updated_at >= created_at)),
    CONSTRAINT administrative_break_glass_validity_ck CHECK ((valid_until > valid_from))
);

COMMENT ON TABLE administration.administrative_break_glass_operation IS 'Administration-owned emergency authority. Strong assurance, short semantic validity, reason/incident evidence and durable obligations are explicit.';

CREATE TABLE administration.administrative_break_glass_review (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    break_glass_operation_id uuid CONSTRAINT administrative_break_glass_re_break_glass_operation_id_not_null NOT NULL,
    obligation_id uuid NOT NULL,
    reviewer_identity_id uuid NOT NULL,
    outcome character varying(48) NOT NULL,
    summary character varying(2048) NOT NULL,
    reviewed_at timestamp with time zone NOT NULL,
    correlation_id uuid,
    causation_id uuid,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT administrative_break_glass_review_outcome_ck CHECK (((outcome)::text = ANY ((ARRAY['APPROVED_USE'::character varying, 'POLICY_CONCERN'::character varying, 'INCIDENT_FOLLOW_UP_REQUIRED'::character varying])::text[]))),
    CONSTRAINT administrative_break_glass_review_summary_ck CHECK ((btrim((summary)::text) <> ''::text))
);

COMMENT ON TABLE administration.administrative_break_glass_review IS 'Immutable Administration-owned post-use review evidence. Review never retroactively changes emergency authority validity.';

CREATE TABLE administration.administrative_delegation (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    delegate_identity_id uuid NOT NULL,
    delegator_identity_id uuid NOT NULL,
    source_grant_id uuid NOT NULL,
    role_id uuid NOT NULL,
    scope_type character varying(48) NOT NULL,
    scope_resource_type character varying(128),
    scope_ref_id uuid,
    scope_key character varying(64),
    state character varying(24) NOT NULL,
    valid_from timestamp with time zone,
    valid_until timestamp with time zone NOT NULL,
    created_by_identity_id uuid NOT NULL,
    revoked_by_identity_id uuid,
    revoked_at timestamp with time zone,
    correlation_id uuid,
    causation_id uuid,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT administration_delegation_revision_ck CHECK ((revision > 0)),
    CONSTRAINT administration_delegation_revocation_shape_ck CHECK (((((state)::text = 'ACTIVE'::text) AND (revoked_by_identity_id IS NULL) AND (revoked_at IS NULL)) OR (((state)::text = 'REVOKED'::text) AND (revoked_by_identity_id IS NOT NULL) AND (revoked_at IS NOT NULL)))),
    CONSTRAINT administration_delegation_scope_shape_ck CHECK (((((scope_type)::text = 'GLOBAL'::text) AND (scope_resource_type IS NULL) AND (scope_ref_id IS NULL) AND (scope_key IS NULL)) OR (((scope_type)::text = 'SPECIFIC_RESOURCE'::text) AND (scope_resource_type IS NOT NULL) AND (btrim((scope_resource_type)::text) <> ''::text) AND (scope_ref_id IS NOT NULL) AND (scope_key IS NULL)) OR (((scope_type)::text = 'CANONICAL_ATTRIBUTE_CLASSIFICATION'::text) AND (scope_resource_type IS NULL) AND (scope_ref_id IS NULL) AND (scope_key IS NOT NULL) AND (btrim((scope_key)::text) <> ''::text)) OR (((scope_type)::text <> ALL ((ARRAY['GLOBAL'::character varying, 'SPECIFIC_RESOURCE'::character varying, 'CANONICAL_ATTRIBUTE_CLASSIFICATION'::character varying])::text[])) AND (scope_resource_type IS NULL) AND (scope_ref_id IS NOT NULL) AND (scope_key IS NULL)))),
    CONSTRAINT administration_delegation_scope_type_ck CHECK (((scope_type)::text = ANY ((ARRAY['GLOBAL'::character varying, 'ORGANIZATION'::character varying, 'APPLICATION'::character varying, 'APPLICATION_TARGET'::character varying, 'SOURCE_SYSTEM'::character varying, 'CONNECTOR_INSTANCE'::character varying, 'IDENTITY_POPULATION'::character varying, 'CANONICAL_ATTRIBUTE_CLASSIFICATION'::character varying, 'SPECIFIC_RESOURCE'::character varying])::text[]))),
    CONSTRAINT administration_delegation_state_ck CHECK (((state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'REVOKED'::character varying])::text[]))),
    CONSTRAINT administration_delegation_timestamp_ck CHECK ((updated_at >= created_at)),
    CONSTRAINT administration_delegation_validity_ck CHECK (((valid_from IS NULL) OR (valid_until > valid_from)))
);

COMMENT ON TABLE administration.administrative_delegation IS 'Administration-owned explicit single-hop delegated authority. Authority remains dependent on the current direct source grant.';

COMMENT ON COLUMN administration.administrative_delegation.delegate_identity_id IS 'Actual governed acting Identity when delegated authority is used; cross-capability stable ID with no database FK.';

COMMENT ON COLUMN administration.administrative_delegation.delegator_identity_id IS 'Governed Identity that owns the direct source grant; provenance only, never substituted as the acting Identity.';

CREATE TABLE administration.administrative_elevation (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    beneficiary_identity_id uuid NOT NULL,
    initiator_identity_id uuid NOT NULL,
    authority_basis_grant_id uuid NOT NULL,
    role_id uuid NOT NULL,
    scope_type character varying(48) NOT NULL,
    scope_resource_type character varying(128),
    scope_ref_id uuid,
    scope_key character varying(64),
    valid_from timestamp with time zone,
    valid_until timestamp with time zone NOT NULL,
    request_fingerprint character varying(64) NOT NULL,
    state character varying(32) NOT NULL,
    approval_case_id uuid,
    approval_plan_fingerprint character varying(64),
    activated_at timestamp with time zone,
    denied_at timestamp with time zone,
    cancelled_at timestamp with time zone,
    revoked_at timestamp with time zone,
    correlation_id uuid,
    causation_id uuid,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT administrative_elevation_approval_shape_ck CHECK ((((approval_case_id IS NULL) AND (approval_plan_fingerprint IS NULL)) OR ((approval_case_id IS NOT NULL) AND (approval_plan_fingerprint IS NOT NULL)))),
    CONSTRAINT administrative_elevation_revision_ck CHECK ((revision > 0)),
    CONSTRAINT administrative_elevation_state_ck CHECK (((state)::text = ANY ((ARRAY['REQUESTED'::character varying, 'PENDING_APPROVAL'::character varying, 'ACTIVE'::character varying, 'DENIED'::character varying, 'CANCELLED'::character varying, 'REVOKED'::character varying])::text[]))),
    CONSTRAINT administrative_elevation_timestamp_ck CHECK ((updated_at >= created_at)),
    CONSTRAINT administrative_elevation_validity_ck CHECK (((valid_from IS NULL) OR (valid_until > valid_from)))
);

COMMENT ON TABLE administration.administrative_elevation IS 'Administration-owned temporary/JIT authority process; Governance ApprovalCase is referenced evidence only.';

CREATE TABLE administration.administrative_grant (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    actor_identity_id uuid NOT NULL,
    role_id uuid NOT NULL,
    scope_type character varying(48) NOT NULL,
    scope_resource_type character varying(128),
    scope_ref_id uuid,
    state character varying(24) NOT NULL,
    valid_from timestamp with time zone,
    valid_until timestamp with time zone,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    scope_key character varying(64),
    grantable boolean DEFAULT false NOT NULL,
    delegable boolean DEFAULT false NOT NULL,
    authority_basis_grant_id uuid,
    CONSTRAINT administration_grant_authority_basis_not_self_ck CHECK (((authority_basis_grant_id IS NULL) OR (authority_basis_grant_id <> id))),
    CONSTRAINT administration_grant_revision_ck CHECK ((revision > 0)),
    CONSTRAINT administration_grant_scope_shape_ck CHECK (((((scope_type)::text = 'GLOBAL'::text) AND (scope_resource_type IS NULL) AND (scope_ref_id IS NULL) AND (scope_key IS NULL)) OR (((scope_type)::text = 'SPECIFIC_RESOURCE'::text) AND (scope_resource_type IS NOT NULL) AND (btrim((scope_resource_type)::text) <> ''::text) AND (scope_ref_id IS NOT NULL) AND (scope_key IS NULL)) OR (((scope_type)::text = 'CANONICAL_ATTRIBUTE_CLASSIFICATION'::text) AND (scope_resource_type IS NULL) AND (scope_ref_id IS NULL) AND (scope_key IS NOT NULL) AND (btrim((scope_key)::text) <> ''::text)) OR (((scope_type)::text <> ALL ((ARRAY['GLOBAL'::character varying, 'SPECIFIC_RESOURCE'::character varying, 'CANONICAL_ATTRIBUTE_CLASSIFICATION'::character varying])::text[])) AND (scope_resource_type IS NULL) AND (scope_ref_id IS NOT NULL) AND (scope_key IS NULL)))),
    CONSTRAINT administration_grant_scope_type_ck CHECK (((scope_type)::text = ANY ((ARRAY['GLOBAL'::character varying, 'ORGANIZATION'::character varying, 'APPLICATION'::character varying, 'APPLICATION_TARGET'::character varying, 'SOURCE_SYSTEM'::character varying, 'CONNECTOR_INSTANCE'::character varying, 'IDENTITY_POPULATION'::character varying, 'CANONICAL_ATTRIBUTE_CLASSIFICATION'::character varying, 'SPECIFIC_RESOURCE'::character varying])::text[]))),
    CONSTRAINT administration_grant_state_ck CHECK (((state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'REVOKED'::character varying])::text[]))),
    CONSTRAINT administration_grant_timestamp_ck CHECK ((updated_at >= created_at)),
    CONSTRAINT administration_grant_validity_ck CHECK (((valid_until IS NULL) OR (valid_from IS NULL) OR (valid_until > valid_from)))
);

COMMENT ON TABLE administration.administrative_grant IS 'Tenant-scoped IAM control-plane authority. actor_identity_id is a cross-capability stable Identity ID and intentionally has no database FK.';

COMMENT ON COLUMN administration.administrative_grant.scope_key IS 'Typed opaque string scope key; currently used only for exact canonical attribute classification scope.';

COMMENT ON COLUMN administration.administrative_grant.grantable IS 'Whether this direct grant may be the explicit authority basis for bounded direct-grant creation/elevation. Does not imply manage-authorization permission.';

COMMENT ON COLUMN administration.administrative_grant.delegable IS 'Whether this direct grant may be the explicit authority basis for bounded delegation. Delegation remains a separate authority object.';

COMMENT ON COLUMN administration.administrative_grant.authority_basis_grant_id IS 'Creation-time provenance for a direct grant. The created direct grant remains independent if the basis is later revoked/expired.';

CREATE TABLE administration.administrative_permission (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    resource_type character varying(128) NOT NULL,
    action character varying(128) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT administration_permission_action_ck CHECK ((btrim((action)::text) <> ''::text)),
    CONSTRAINT administration_permission_resource_ck CHECK ((btrim((resource_type)::text) <> ''::text))
);

COMMENT ON TABLE administration.administrative_permission IS 'Administration-owned stable semantic IAM control-plane permissions.';

CREATE TABLE administration.administrative_role (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    code character varying(128) NOT NULL,
    name character varying(512) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT administration_role_code_ck CHECK ((btrim((code)::text) <> ''::text)),
    CONSTRAINT administration_role_name_ck CHECK ((btrim((name)::text) <> ''::text)),
    CONSTRAINT administration_role_revision_ck CHECK ((revision > 0)),
    CONSTRAINT administration_role_timestamp_ck CHECK ((updated_at >= created_at))
);

COMMENT ON TABLE administration.administrative_role IS 'Administration-owned permission bundles; distinct from Catalog IAM Roles.';

CREATE TABLE administration.administrative_role_permission (
    tenant_id uuid NOT NULL,
    role_id uuid NOT NULL,
    permission_id uuid NOT NULL,
    created_at timestamp with time zone NOT NULL
);

CREATE TABLE administration.control_plane_actor_binding (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    external_subject_key character(64) NOT NULL,
    issuer character varying(512) NOT NULL,
    subject character varying(512) NOT NULL,
    actor_identity_id uuid NOT NULL,
    state character varying(24) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT administration_actor_binding_issuer_ck CHECK (((btrim((issuer)::text) <> ''::text) AND ((issuer)::text = btrim((issuer)::text)))),
    CONSTRAINT administration_actor_binding_revision_ck CHECK ((revision > 0)),
    CONSTRAINT administration_actor_binding_state_ck CHECK (((state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'REVOKED'::character varying])::text[]))),
    CONSTRAINT administration_actor_binding_subject_ck CHECK (((btrim((subject)::text) <> ''::text) AND ((subject)::text = btrim((subject)::text)))),
    CONSTRAINT administration_actor_binding_subject_key_ck CHECK ((external_subject_key ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT administration_actor_binding_timestamp_ck CHECK ((updated_at >= created_at))
);

COMMENT ON TABLE administration.control_plane_actor_binding IS 'Administration-owned mapping from a validated external issuer+subject to one tenant-scoped governed Identity. The binding never grants IAM permission by itself.';

COMMENT ON COLUMN administration.control_plane_actor_binding.external_subject_key IS 'SHA-256 lookup key over length-delimited issuer and subject. Exact issuer+subject remain authoritative and are rechecked on lookup.';

COMMENT ON COLUMN administration.control_plane_actor_binding.actor_identity_id IS 'Cross-capability stable Identity ID; intentionally no database FK to Identity.';

CREATE TABLE administration.initial_admin_bootstrap (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    actor_identity_id uuid NOT NULL,
    actor_binding_id uuid,
    administrative_role_id uuid NOT NULL,
    administrative_grant_id uuid NOT NULL,
    completed_at timestamp with time zone NOT NULL,
    correlation_id uuid NOT NULL,
    authentication_anchor_type character varying(32) NOT NULL,
    actor_principal_id uuid,
    CONSTRAINT administration_initial_bootstrap_anchor_shape_ck CHECK (((((authentication_anchor_type)::text = 'EXTERNAL_SUBJECT'::text) AND (actor_binding_id IS NOT NULL) AND (actor_principal_id IS NULL)) OR (((authentication_anchor_type)::text = 'FIRST_PARTY_PRINCIPAL'::text) AND (actor_binding_id IS NULL) AND (actor_principal_id IS NOT NULL)))),
    CONSTRAINT administration_initial_bootstrap_anchor_type_ck CHECK (((authentication_anchor_type)::text = ANY ((ARRAY['EXTERNAL_SUBJECT'::character varying, 'FIRST_PARTY_PRINCIPAL'::character varying])::text[])))
);

COMMENT ON TABLE administration.initial_admin_bootstrap IS 'Immutable burn-once tenant marker for first-administrator provisioning. Its unique tenant row permanently closes the bootstrap path after the successful transaction commits.';

COMMENT ON COLUMN administration.initial_admin_bootstrap.actor_identity_id IS 'Governed Identity selected as the first administrator; intentionally no cross-capability database FK.';

COMMENT ON COLUMN administration.initial_admin_bootstrap.authentication_anchor_type IS 'Typed authentication source for the immutable first-admin marker: external subject binding or first-party governed Principal.';

COMMENT ON COLUMN administration.initial_admin_bootstrap.actor_principal_id IS 'Cross-capability stable Principal ID used only for FIRST_PARTY_PRINCIPAL bootstrap; intentionally no database FK to Identity-owned state.';

ALTER TABLE ONLY administration.control_plane_actor_binding
    ADD CONSTRAINT administration_actor_binding_subject_key_uq UNIQUE (external_subject_key);

ALTER TABLE ONLY administration.control_plane_actor_binding
    ADD CONSTRAINT administration_actor_binding_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY administration.administrative_delegation
    ADD CONSTRAINT administration_delegation_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY administration.administrative_grant
    ADD CONSTRAINT administration_grant_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY administration.initial_admin_bootstrap
    ADD CONSTRAINT administration_initial_bootstrap_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY administration.initial_admin_bootstrap
    ADD CONSTRAINT administration_initial_bootstrap_tenant_uq UNIQUE (tenant_id);

ALTER TABLE ONLY administration.administrative_permission
    ADD CONSTRAINT administration_permission_key_uq UNIQUE (tenant_id, resource_type, action);

ALTER TABLE ONLY administration.administrative_permission
    ADD CONSTRAINT administration_permission_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY administration.administrative_role
    ADD CONSTRAINT administration_role_code_uq UNIQUE (tenant_id, code);

ALTER TABLE ONLY administration.administrative_role
    ADD CONSTRAINT administration_role_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY administration.administrative_break_glass_obligation
    ADD CONSTRAINT administrative_break_glass_obligation_pkey PRIMARY KEY (id);

ALTER TABLE ONLY administration.administrative_break_glass_obligation
    ADD CONSTRAINT administrative_break_glass_obligation_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY administration.administrative_break_glass_obligation
    ADD CONSTRAINT administrative_break_glass_obligation_uq UNIQUE (tenant_id, break_glass_operation_id, obligation_type);

ALTER TABLE ONLY administration.administrative_break_glass_operation
    ADD CONSTRAINT administrative_break_glass_operation_pkey PRIMARY KEY (id);

ALTER TABLE ONLY administration.administrative_break_glass_review
    ADD CONSTRAINT administrative_break_glass_review_one_per_obligation_uq UNIQUE (tenant_id, obligation_id);

ALTER TABLE ONLY administration.administrative_break_glass_review
    ADD CONSTRAINT administrative_break_glass_review_one_per_operation_uq UNIQUE (tenant_id, break_glass_operation_id);

ALTER TABLE ONLY administration.administrative_break_glass_review
    ADD CONSTRAINT administrative_break_glass_review_pkey PRIMARY KEY (id);

ALTER TABLE ONLY administration.administrative_break_glass_review
    ADD CONSTRAINT administrative_break_glass_review_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY administration.administrative_break_glass_operation
    ADD CONSTRAINT administrative_break_glass_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY administration.administrative_delegation
    ADD CONSTRAINT administrative_delegation_pkey PRIMARY KEY (id);

ALTER TABLE ONLY administration.administrative_elevation
    ADD CONSTRAINT administrative_elevation_pkey PRIMARY KEY (id);

ALTER TABLE ONLY administration.administrative_elevation
    ADD CONSTRAINT administrative_elevation_tenant_id_uq UNIQUE (tenant_id, id);

ALTER TABLE ONLY administration.administrative_grant
    ADD CONSTRAINT administrative_grant_pkey PRIMARY KEY (id);

ALTER TABLE ONLY administration.administrative_permission
    ADD CONSTRAINT administrative_permission_pkey PRIMARY KEY (id);

ALTER TABLE ONLY administration.administrative_role_permission
    ADD CONSTRAINT administrative_role_permission_pkey PRIMARY KEY (tenant_id, role_id, permission_id);

ALTER TABLE ONLY administration.administrative_role
    ADD CONSTRAINT administrative_role_pkey PRIMARY KEY (id);

ALTER TABLE ONLY administration.control_plane_actor_binding
    ADD CONSTRAINT control_plane_actor_binding_pkey PRIMARY KEY (id);

ALTER TABLE ONLY administration.initial_admin_bootstrap
    ADD CONSTRAINT initial_admin_bootstrap_pkey PRIMARY KEY (id);

CREATE INDEX administration_actor_binding_tenant_actor_idx ON administration.control_plane_actor_binding USING btree (tenant_id, actor_identity_id, state);

CREATE INDEX administration_delegation_delegate_state_idx ON administration.administrative_delegation USING btree (tenant_id, delegate_identity_id, state, role_id, valid_until);

CREATE INDEX administration_delegation_role_state_idx ON administration.administrative_delegation USING btree (tenant_id, role_id, state, valid_until);

CREATE INDEX administration_delegation_source_state_idx ON administration.administrative_delegation USING btree (tenant_id, source_grant_id, state, valid_until);

CREATE INDEX administration_grant_actor_state_idx ON administration.administrative_grant USING btree (tenant_id, actor_identity_id, state, role_id);

CREATE INDEX administration_grant_authority_basis_idx ON administration.administrative_grant USING btree (tenant_id, authority_basis_grant_id) WHERE (authority_basis_grant_id IS NOT NULL);

CREATE INDEX administration_role_permission_permission_idx ON administration.administrative_role_permission USING btree (tenant_id, permission_id, role_id);

CREATE INDEX administrative_break_glass_actor_state_idx ON administration.administrative_break_glass_operation USING btree (tenant_id, actor_identity_id, state, valid_until);

CREATE INDEX administrative_break_glass_obligation_pending_idx ON administration.administrative_break_glass_obligation USING btree (tenant_id, state, obligation_type, created_at, id);

CREATE INDEX administrative_break_glass_review_reviewer_idx ON administration.administrative_break_glass_review USING btree (tenant_id, reviewer_identity_id, reviewed_at DESC, id DESC);

CREATE INDEX administrative_break_glass_role_state_idx ON administration.administrative_break_glass_operation USING btree (tenant_id, role_id, state, valid_until);

CREATE INDEX administrative_elevation_beneficiary_state_idx ON administration.administrative_elevation USING btree (tenant_id, beneficiary_identity_id, state, valid_until);

CREATE INDEX administrative_elevation_role_state_idx ON administration.administrative_elevation USING btree (tenant_id, role_id, state, valid_until);

ALTER TABLE ONLY administration.control_plane_actor_binding
    ADD CONSTRAINT administration_actor_binding_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY administration.administrative_delegation
    ADD CONSTRAINT administration_delegation_role_fk FOREIGN KEY (tenant_id, role_id) REFERENCES administration.administrative_role(tenant_id, id);

ALTER TABLE ONLY administration.administrative_delegation
    ADD CONSTRAINT administration_delegation_source_grant_fk FOREIGN KEY (tenant_id, source_grant_id) REFERENCES administration.administrative_grant(tenant_id, id);

ALTER TABLE ONLY administration.administrative_delegation
    ADD CONSTRAINT administration_delegation_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY administration.administrative_grant
    ADD CONSTRAINT administration_grant_authority_basis_fk FOREIGN KEY (tenant_id, authority_basis_grant_id) REFERENCES administration.administrative_grant(tenant_id, id);

ALTER TABLE ONLY administration.administrative_grant
    ADD CONSTRAINT administration_grant_role_fk FOREIGN KEY (tenant_id, role_id) REFERENCES administration.administrative_role(tenant_id, id);

ALTER TABLE ONLY administration.administrative_grant
    ADD CONSTRAINT administration_grant_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY administration.initial_admin_bootstrap
    ADD CONSTRAINT administration_initial_bootstrap_binding_fk FOREIGN KEY (tenant_id, actor_binding_id) REFERENCES administration.control_plane_actor_binding(tenant_id, id) DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE ONLY administration.initial_admin_bootstrap
    ADD CONSTRAINT administration_initial_bootstrap_grant_fk FOREIGN KEY (tenant_id, administrative_grant_id) REFERENCES administration.administrative_grant(tenant_id, id) DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE ONLY administration.initial_admin_bootstrap
    ADD CONSTRAINT administration_initial_bootstrap_role_fk FOREIGN KEY (tenant_id, administrative_role_id) REFERENCES administration.administrative_role(tenant_id, id) DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE ONLY administration.initial_admin_bootstrap
    ADD CONSTRAINT administration_initial_bootstrap_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY administration.administrative_permission
    ADD CONSTRAINT administration_permission_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY administration.administrative_role_permission
    ADD CONSTRAINT administration_role_permission_permission_fk FOREIGN KEY (tenant_id, permission_id) REFERENCES administration.administrative_permission(tenant_id, id);

ALTER TABLE ONLY administration.administrative_role_permission
    ADD CONSTRAINT administration_role_permission_role_fk FOREIGN KEY (tenant_id, role_id) REFERENCES administration.administrative_role(tenant_id, id);

ALTER TABLE ONLY administration.administrative_role
    ADD CONSTRAINT administration_role_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY administration.administrative_break_glass_obligation
    ADD CONSTRAINT administrative_break_glass_obligation_operation_fk FOREIGN KEY (tenant_id, break_glass_operation_id) REFERENCES administration.administrative_break_glass_operation(tenant_id, id);

ALTER TABLE ONLY administration.administrative_break_glass_obligation
    ADD CONSTRAINT administrative_break_glass_obligation_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY administration.administrative_break_glass_review
    ADD CONSTRAINT administrative_break_glass_review_obligation_fk FOREIGN KEY (tenant_id, obligation_id) REFERENCES administration.administrative_break_glass_obligation(tenant_id, id);

ALTER TABLE ONLY administration.administrative_break_glass_review
    ADD CONSTRAINT administrative_break_glass_review_operation_fk FOREIGN KEY (tenant_id, break_glass_operation_id) REFERENCES administration.administrative_break_glass_operation(tenant_id, id);

ALTER TABLE ONLY administration.administrative_break_glass_review
    ADD CONSTRAINT administrative_break_glass_review_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY administration.administrative_break_glass_operation
    ADD CONSTRAINT administrative_break_glass_role_fk FOREIGN KEY (tenant_id, role_id) REFERENCES administration.administrative_role(tenant_id, id);

ALTER TABLE ONLY administration.administrative_break_glass_operation
    ADD CONSTRAINT administrative_break_glass_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);

ALTER TABLE ONLY administration.administrative_elevation
    ADD CONSTRAINT administrative_elevation_basis_fk FOREIGN KEY (tenant_id, authority_basis_grant_id) REFERENCES administration.administrative_grant(tenant_id, id);

ALTER TABLE ONLY administration.administrative_elevation
    ADD CONSTRAINT administrative_elevation_role_fk FOREIGN KEY (tenant_id, role_id) REFERENCES administration.administrative_role(tenant_id, id);

ALTER TABLE ONLY administration.administrative_elevation
    ADD CONSTRAINT administrative_elevation_tenant_fk FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);
