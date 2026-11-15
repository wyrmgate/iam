-- ADR-0077: Integration authority for explicit upstream trust and immutable subject mapping.
CREATE TABLE integration.federation_trust (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenancy.tenant(id), revision bigint NOT NULL CHECK(revision>0),
 protocol varchar(16) NOT NULL CHECK(protocol='OIDC'), provider_key varchar(128) NOT NULL CHECK(provider_key='oidc-code-pkce-rs256'),
 issuer varchar(512) COLLATE "C" NOT NULL, audience varchar(512) COLLATE "C" NOT NULL,
 authorization_endpoint varchar(2048) NOT NULL, token_endpoint varchar(2048) NOT NULL,
 key_id varchar(128) NOT NULL, public_key_spki varchar(4096) NOT NULL,
 lifecycle_state varchar(16) NOT NULL CHECK(lifecycle_state IN ('ACTIVE','RETIRED')),
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, retired_at timestamptz,
 UNIQUE(tenant_id,id), CHECK(updated_at>=created_at),
 CHECK((lifecycle_state='ACTIVE' AND retired_at IS NULL) OR (lifecycle_state='RETIRED' AND retired_at IS NOT NULL))
);
CREATE UNIQUE INDEX federation_trust_active_issuer_audience_uq ON integration.federation_trust(tenant_id,issuer,audience) WHERE lifecycle_state='ACTIVE';
CREATE TABLE integration.federated_subject_link (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, trust_id uuid NOT NULL, revision bigint NOT NULL CHECK(revision>0),
 exact_subject varchar(512) COLLATE "C" NOT NULL CHECK(btrim(exact_subject)<>''),
 principal_id uuid NOT NULL, lifecycle_state varchar(16) NOT NULL CHECK(lifecycle_state IN ('ACTIVE','RETIRED')),
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, retired_at timestamptz,
 UNIQUE(tenant_id,id), FOREIGN KEY(tenant_id,trust_id) REFERENCES integration.federation_trust(tenant_id,id),
 CHECK(updated_at>=created_at), CHECK((lifecycle_state='ACTIVE' AND retired_at IS NULL) OR (lifecycle_state='RETIRED' AND retired_at IS NOT NULL))
);
CREATE UNIQUE INDEX federated_subject_active_exact_uq ON integration.federated_subject_link(tenant_id,trust_id,exact_subject) WHERE lifecycle_state='ACTIVE';
CREATE INDEX federated_subject_principal_idx ON integration.federated_subject_link(tenant_id,principal_id);
