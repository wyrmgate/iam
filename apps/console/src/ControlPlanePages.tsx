import { useState } from 'react';
import { V1_ROOT, withCursor } from './api';
import { CurrentAdministrativeAuthorityPage } from './CurrentAdministrativeAuthorityPage';
import { IntegrationOperationsPage } from './IntegrationOperationsPage';
import { type Field, type Spec, type Values, Workspace, WorkspaceTabs } from './SemanticWorkspace';
import { Box, Button, Page, PageHeader, StatePanel, Surface, TextInput } from './ui';

const nullable = (value: string | boolean | undefined) => String(value ?? '').trim() || null;
const iso = (value: string | boolean | undefined) => nullable(value) ? new Date(String(value)).toISOString() : null;
const csv = (value: string | boolean | undefined) => String(value ?? '').split(/[\n,]/).map((item) => item.trim()).filter(Boolean);
const list = (path: string, extra?: Record<string, string | undefined>) => (cursor?: string | null) => withCursor(path, cursor, extra);
const scopeFields: Field[] = [
  { name: 'scopeType', label: 'Scope type', type: 'select', options: ['GLOBAL', 'ORGANIZATION', 'APPLICATION', 'APPLICATION_TARGET', 'SOURCE_SYSTEM', 'CONNECTOR_INSTANCE', 'IDENTITY_POPULATION', 'CANONICAL_ATTRIBUTE_CLASSIFICATION', 'SPECIFIC_RESOURCE'], required: true },
  { name: 'scopeResourceType', label: 'Resource type' },
  { name: 'scopeResourceId', label: 'Resource ID' },
  { name: 'scopeKey', label: 'Scope key' },
];
const scope = (values: Values) => ({ type: values.scopeType, resourceType: nullable(values.scopeResourceType), resourceId: nullable(values.scopeResourceId), scopeKey: nullable(values.scopeKey) });

const principalSpec: Spec = {
  eyebrow: 'Identity', title: 'Principals', description: 'Technical account authority owned by Identity; provider observation is not Principal authority.',
  list: list(`${V1_ROOT}/principals`), detail: (id) => `${V1_ROOT}/principals/${encodeURIComponent(id)}`,
  columns: ['nativePrincipalKey', 'applicationTargetId', 'identityId', 'lifecycleState'],
  create: { path: `${V1_ROOT}/principals`, fields: [{ name: 'applicationTargetId', label: 'Application Target ID', required: true }, { name: 'nativePrincipalKey', label: 'Native principal key', required: true }], body: (values) => ({ applicationTargetId: values.applicationTargetId, nativePrincipalKey: values.nativePrincipalKey }) },
  actions: [{ label: 'Correlate to Identity', path: (id) => `${V1_ROOT}/principals/${encodeURIComponent(id)}:correlate`, fields: [{ name: 'identityId', label: 'Identity ID', required: true }], body: (values) => ({ identityId: values.identityId }) }],
};

const applicationSpec: Spec = {
  eyebrow: 'Catalog', title: 'Applications', description: 'Governed Application authority.',
  list: list(`${V1_ROOT}/applications`), detail: (id) => `${V1_ROOT}/applications/${encodeURIComponent(id)}`,
  columns: ['code', 'name', 'lifecycleState', 'revision'],
  create: { path: `${V1_ROOT}/applications`, fields: [{ name: 'code', label: 'Code', required: true }, { name: 'name', label: 'Name', required: true }], body: (values) => ({ code: values.code, name: values.name }) },
  actions: [{ label: 'Retire', path: (id) => `${V1_ROOT}/applications/${encodeURIComponent(id)}/retire`, destructive: true }],
};

const roleSpec: Spec = {
  eyebrow: 'Catalog', title: 'Roles', description: 'Business/application Role authority; composition is versioned separately.',
  list: list(`${V1_ROOT}/roles`), detail: (id) => `${V1_ROOT}/roles/${encodeURIComponent(id)}`,
  columns: ['code', 'name', 'roleType', 'lifecycleState'],
  create: { path: `${V1_ROOT}/roles`, fields: [{ name: 'roleType', label: 'Role type', type: 'select', options: ['BUSINESS', 'APPLICATION'], required: true }, { name: 'applicationId', label: 'Application ID' }, { name: 'code', label: 'Code', required: true }, { name: 'name', label: 'Name', required: true }], body: (values) => ({ roleType: values.roleType, applicationId: nullable(values.applicationId), code: values.code, name: values.name }) },
  actions: [{ label: 'Retire', path: (id) => `${V1_ROOT}/roles/${encodeURIComponent(id)}/retire`, destructive: true }],
};

const assignmentSpec: Spec = {
  eyebrow: 'Access', title: 'Access Assignments', description: 'Authoritative business access intent.',
  list: list(`${V1_ROOT}/access-assignments`), detail: (id) => `${V1_ROOT}/access-assignments/${encodeURIComponent(id)}`,
  columns: ['identityId', 'targetKind', 'lifecycleState', 'principalConstraintKind'],
  create: {
    path: `${V1_ROOT}/access-assignments`,
    fields: [
      { name: 'identityId', label: 'Identity ID', required: true },
      { name: 'targetKind', label: 'Target kind', type: 'select', options: ['ROLE', 'ENTITLEMENT'], required: true },
      { name: 'roleId', label: 'Role ID' }, { name: 'entitlementId', label: 'Entitlement ID' },
      { name: 'principalConstraintKind', label: 'Principal constraint', type: 'select', options: ['ANY', 'SPECIFIC'], required: true },
      { name: 'specificPrincipalId', label: 'Specific Principal ID' },
      { name: 'validFrom', label: 'Valid from', type: 'datetime-local' }, { name: 'validUntil', label: 'Valid until', type: 'datetime-local' },
    ],
    body: (values) => ({ identityId: values.identityId, targetKind: values.targetKind, roleId: nullable(values.roleId), entitlementId: nullable(values.entitlementId), principalConstraintKind: values.principalConstraintKind, specificPrincipalId: nullable(values.specificPrincipalId), validFrom: iso(values.validFrom), validUntil: iso(values.validUntil) }),
  },
  actions: ['suspend', 'resume', 'cancel', 'revoke'].map((name) => ({ label: name[0].toUpperCase() + name.slice(1), path: (id) => `${V1_ROOT}/access-assignments/${encodeURIComponent(id)}/${name}`, destructive: name === 'cancel' || name === 'revoke' })),
};

const effectiveSpec: Spec = {
  eyebrow: 'Access · projection', title: 'Effective Access', description: 'Read-only entitlement-level projection, never editable business intent.',
  list: list(`${V1_ROOT}/effective-access`), detail: (id) => `${V1_ROOT}/effective-access/${encodeURIComponent(id)}`,
  columns: ['identityId', 'entitlementId', 'principalConstraintKey', 'supportCount'],
};

const approvalSpec: Spec = {
  eyebrow: 'Governance', title: 'Approval Inbox', description: 'Current reusable approval work.',
  list: list(`${V1_ROOT}/governance/approval-inbox`), detail: (id) => `${V1_ROOT}/governance/approvals/${encodeURIComponent(id)}`,
  columns: ['subjectKind', 'subjectId', 'state', 'currentStageOrdinal'],
  actions: [
    { label: 'Approve', path: (id) => `${V1_ROOT}/governance/approvals/${encodeURIComponent(id)}/approve`, fields: [{ name: 'reason', label: 'Reason', type: 'textarea' }], body: (values) => ({ reason: nullable(values.reason) }) },
    { label: 'Reject', path: (id) => `${V1_ROOT}/governance/approvals/${encodeURIComponent(id)}/reject`, destructive: true, fields: [{ name: 'reason', label: 'Reason', type: 'textarea' }], body: (values) => ({ reason: nullable(values.reason) }) },
  ],
};

const campaignSpec: Spec = {
  eyebrow: 'Governance', title: 'Review Campaigns', description: 'Access certification campaign state, separate from remediation state.',
  list: list(`${V1_ROOT}/governance/review-campaigns`), detail: (id) => `${V1_ROOT}/governance/review-campaigns/${encodeURIComponent(id)}`,
  columns: ['subjectIdentityId', 'reviewerIdentityId', 'state', 'generatedItemCount'],
  create: { path: `${V1_ROOT}/governance/review-campaigns`, fields: [{ name: 'subjectIdentityId', label: 'Subject Identity ID', required: true }, { name: 'reviewerIdentityId', label: 'Reviewer Identity ID', required: true }, { name: 'snapshotAt', label: 'Snapshot at', type: 'datetime-local', required: true }], body: (values) => ({ subjectIdentityId: values.subjectIdentityId, reviewerIdentityId: values.reviewerIdentityId, snapshotAt: iso(values.snapshotAt) }) },
  actions: [{ label: 'Start', path: (id) => `${V1_ROOT}/governance/review-campaigns/${encodeURIComponent(id)}/start` }],
};

const reviewSpec: Spec = {
  eyebrow: 'Governance', title: 'Review Inbox', description: 'Current reviewer work; KEEP/REVOKE remain semantic decisions.',
  list: list(`${V1_ROOT}/governance/review-inbox`), detail: (id) => `${V1_ROOT}/governance/review-items/${encodeURIComponent(id)}`,
  columns: ['identityId', 'targetKind', 'state', 'decision'],
  actions: [
    { label: 'Keep', path: (id) => `${V1_ROOT}/governance/review-items/${encodeURIComponent(id)}/keep`, fields: [{ name: 'reason', label: 'Reason', type: 'textarea' }], body: (values) => ({ reason: nullable(values.reason) }) },
    { label: 'Revoke', path: (id) => `${V1_ROOT}/governance/review-items/${encodeURIComponent(id)}/revoke`, destructive: true, fields: [{ name: 'reason', label: 'Reason', type: 'textarea' }], body: (values) => ({ reason: nullable(values.reason) }) },
  ],
};

const adminRoleSpec: Spec = {
  eyebrow: 'Administration', title: 'Administrative Roles', description: 'Control-plane permission sets, distinct from Catalog Roles and token claims.',
  list: list(`${V1_ROOT}/administrative-roles`), detail: (id) => `${V1_ROOT}/administrative-roles/${encodeURIComponent(id)}`,
  columns: ['code', 'name', 'revision'],
  create: { path: `${V1_ROOT}/administrative-roles`, fields: [{ name: 'code', label: 'Code', required: true }, { name: 'name', label: 'Name', required: true }, { name: 'permissions', label: 'Permissions', type: 'textarea', required: true }], body: (values) => ({ code: values.code, name: values.name, permissions: csv(values.permissions) }) },
  actions: [
    { label: 'Add permission', path: (id) => `${V1_ROOT}/administrative-roles/${encodeURIComponent(id)}:add-permission`, fields: [{ name: 'permission', label: 'Permission', required: true }], body: (values) => ({ permission: values.permission }) },
    { label: 'Remove permission', path: (id) => `${V1_ROOT}/administrative-roles/${encodeURIComponent(id)}:remove-permission`, destructive: true, fields: [{ name: 'permission', label: 'Permission', required: true }], body: (values) => ({ permission: values.permission }) },
  ],
};

function ScopedCatalog({ kind }: { kind: 'targets' | 'entitlements' | 'versions' }) {
  const [parent, setParent] = useState('');
  const [active, setActive] = useState('');
  const parentType = kind === 'versions' ? 'Role ID' : 'Application ID';
  const title = kind === 'targets' ? 'Application Targets' : kind === 'entitlements' ? 'Entitlements' : 'Role Versions';

  if (!active) {
    return (
      <Page>
        <PageHeader eyebrow="Catalog · scoped" title={title} description={`Open ${title.toLowerCase()} for one governed parent resource.`} />
        <Surface title={`Choose ${parentType}`}>
          <Box sx={{ display: 'flex', flexDirection: { xs: 'column', sm: 'row' }, gap: 1 }}>
            <TextInput label={parentType} value={parent} onChange={(event) => setParent(event.target.value)} sx={{ flex: 1 }} />
            <Button variant="contained" disabled={!parent} onClick={() => setActive(parent)}>Open</Button>
          </Box>
        </Surface>
      </Page>
    );
  }

  if (kind === 'targets') return <Workspace spec={{ eyebrow: 'Catalog', title: 'Application Targets', description: 'Technical targets belonging to one governed Application.', list: list(`${V1_ROOT}/applications/${encodeURIComponent(active)}/targets`), detail: (id) => `${V1_ROOT}/application-targets/${encodeURIComponent(id)}`, columns: ['code', 'lifecycleState', 'revision'], create: { path: `${V1_ROOT}/applications/${encodeURIComponent(active)}/targets`, fields: [{ name: 'code', label: 'Code', required: true }], body: (values) => ({ code: values.code }) }, actions: [{ label: 'Retire', path: (id) => `${V1_ROOT}/application-targets/${encodeURIComponent(id)}/retire`, destructive: true }] }} />;
  if (kind === 'entitlements') return <Workspace spec={{ eyebrow: 'Catalog', title: 'Entitlements', description: 'Governed Entitlement authority for one Application.', list: list(`${V1_ROOT}/applications/${encodeURIComponent(active)}/entitlements`), detail: (id) => `${V1_ROOT}/entitlements/${encodeURIComponent(id)}`, columns: ['code', 'entitlementType', 'lifecycleState'], create: { path: `${V1_ROOT}/applications/${encodeURIComponent(active)}/entitlements`, fields: [{ name: 'applicationTargetId', label: 'Application Target ID' }, { name: 'code', label: 'Code', required: true }, { name: 'nativeKey', label: 'Native key' }, { name: 'entitlementType', label: 'Entitlement type', required: true }], body: (values) => ({ applicationTargetId: nullable(values.applicationTargetId), code: values.code, nativeKey: nullable(values.nativeKey), entitlementType: values.entitlementType }) }, actions: [{ label: 'Retire', path: (id) => `${V1_ROOT}/entitlements/${encodeURIComponent(id)}/retire`, destructive: true }] }} />;
  return <Workspace spec={{ eyebrow: 'Catalog · composition', title: 'Role Versions', description: 'Typed immutable Role/Entitlement composition.', list: list(`${V1_ROOT}/roles/${encodeURIComponent(active)}/versions`), detail: (id) => `${V1_ROOT}/roles/${encodeURIComponent(active)}/versions/${encodeURIComponent(id)}`, columns: ['versionNumber', 'state', 'revision'], create: { path: `${V1_ROOT}/roles/${encodeURIComponent(active)}/versions`, fields: [{ name: 'members', label: 'Members', type: 'textarea', required: true, placeholder: 'ENTITLEMENT:<uuid>\nAPPLICATION_ROLE:<uuid>' }], body: (values) => ({ members: String(values.members).split(/\n/).map((line) => line.trim()).filter(Boolean).map((line) => { const [kindName, memberId] = line.split(':', 2); return kindName === 'ENTITLEMENT' ? { kind: 'ENTITLEMENT', entitlementId: memberId } : { kind: 'APPLICATION_ROLE', roleId: memberId }; }) }) }, actions: [{ label: 'Validate', path: (id) => `${V1_ROOT}/roles/${encodeURIComponent(active)}/versions/${encodeURIComponent(id)}/validate` }, { label: 'Activate', path: (id) => `${V1_ROOT}/roles/${encodeURIComponent(active)}/versions/${encodeURIComponent(id)}/activate` }] }} />;
}

function AccessRequestPage() {
  return <Workspace spec={{ eyebrow: 'Governance', title: 'Access Requests', description: 'Create/read/submit typed access requests.', detail: (id) => `${V1_ROOT}/governance/access-requests/${encodeURIComponent(id)}`, create: { path: `${V1_ROOT}/governance/access-requests`, fields: [{ name: 'beneficiaryIdentityId', label: 'Beneficiary Identity ID', required: true }, { name: 'targetKind', label: 'Target kind', type: 'select', options: ['ROLE', 'ENTITLEMENT'], required: true }, { name: 'targetId', label: 'Target ID', required: true }, { name: 'principalConstraintKind', label: 'Principal constraint', type: 'select', options: ['ANY', 'SPECIFIC'], required: true }, { name: 'specificPrincipalId', label: 'Specific Principal ID' }], body: (values) => ({ beneficiaryIdentityId: values.beneficiaryIdentityId, items: [{ targetKind: values.targetKind, targetId: values.targetId, principalConstraintKind: values.principalConstraintKind, specificPrincipalId: nullable(values.specificPrincipalId), validFrom: null, validUntil: null }] }) }, actions: [{ label: 'Submit', path: (id) => `${V1_ROOT}/governance/access-requests/${encodeURIComponent(id)}/submit` }] }} />;
}

function CredentialPageInner() {
  const [principal, setPrincipal] = useState('');
  const [active, setActive] = useState('');
  if (!active) {
    return (
      <Page>
        <PageHeader eyebrow="Credential" title="Credentials" description="Credential list is contractually scoped to one Principal." />
        <Surface title="Choose Principal">
          <Box sx={{ display: 'flex', flexDirection: { xs: 'column', sm: 'row' }, gap: 1 }}>
            <TextInput label="Principal ID" value={principal} onChange={(event) => setPrincipal(event.target.value)} sx={{ flex: 1 }} />
            <Button variant="contained" disabled={!principal} onClick={() => setActive(principal)}>Open</Button>
          </Box>
        </Surface>
      </Page>
    );
  }
  return <Workspace spec={{ eyebrow: 'Credential', title: 'Credentials', description: 'Metadata/control plane only; raw secret/private material must never enter this UI.', list: list(`${V1_ROOT}/credentials`, { principalId: active }), detail: (id) => `${V1_ROOT}/credentials/${encodeURIComponent(id)}`, columns: ['kind', 'lifecycleState', 'validFrom', 'validUntil'], create: { path: `${V1_ROOT}/credentials`, warning: 'Enter only an opaque external secret reference, never the secret/private material itself.', fields: [{ name: 'principalId', label: 'Principal ID', required: true }, { name: 'kind', label: 'Kind', type: 'select', options: ['PASSWORD', 'API_KEY', 'SSH_KEY', 'CERTIFICATE', 'OAUTH_CLIENT_SECRET'], required: true }, { name: 'providerType', label: 'Secret provider type', required: true }, { name: 'referenceKey', label: 'Opaque reference key', required: true }, { name: 'validFrom', label: 'Valid from', type: 'datetime-local' }, { name: 'validUntil', label: 'Valid until', type: 'datetime-local' }], body: (values) => ({ principalId: values.principalId, kind: values.kind, secretReference: { providerType: values.providerType, referenceKey: values.referenceKey }, validFrom: iso(values.validFrom), validUntil: iso(values.validUntil) }) }, actions: [{ label: 'Revoke', path: (id) => `${V1_ROOT}/credentials/${encodeURIComponent(id)}:revoke`, destructive: true }, { label: 'Compromise', path: (id) => `${V1_ROOT}/credentials/${encodeURIComponent(id)}:compromise`, destructive: true }, { label: 'Plan rotation', path: (id) => `${V1_ROOT}/credentials/${encodeURIComponent(id)}:rotate`, requireEtag: false }] }} />;
}

function IntegrationPageInner() {
  const connector: Spec = { eyebrow: 'Integration', title: 'Connector Instances', description: 'Integration-owned connector lifecycle/configuration. Provider observation is not Catalog/Access authority.', detail: (id) => `${V1_ROOT}/connectors/${encodeURIComponent(id)}`, create: { path: `${V1_ROOT}/connectors`, fields: [{ name: 'connectorType', label: 'Connector type', required: true }, { name: 'runtimeId', label: 'Runtime ID', required: true }, { name: 'runtimeVersion', label: 'Runtime version', required: true }, { name: 'configurationVersion', label: 'Configuration version', type: 'number', required: true }, { name: 'configuration', label: 'Provider configuration JSON', type: 'textarea', required: true, help: 'Do not place secrets here.' }, { name: 'secretReference', label: 'Opaque secret reference' }], body: (values) => ({ connectorType: values.connectorType, runtimeId: values.runtimeId, runtimeVersion: values.runtimeVersion, configurationVersion: Number(values.configurationVersion), configuration: JSON.parse(String(values.configuration || '{}')), secretReference: nullable(values.secretReference) }) }, actions: [{ label: 'Disable', path: (id) => `${V1_ROOT}/connectors/${encodeURIComponent(id)}:disable`, destructive: true }] };
  return <WorkspaceTabs tabs={[{ id: 'connector', label: 'Connectors', content: <Workspace spec={connector} /> }, { id: 'binding', label: 'Bindings', content: <Workspace spec={{ eyebrow: 'Integration', title: 'Connector Bindings', description: 'Public binding administration lookup. The current checked-in contract does not define a request schema for create/update, so the browser does not invent one.', detail: (id) => `${V1_ROOT}/connector-bindings/${encodeURIComponent(id)}`, actions: [{ label: 'Disable', path: (id) => `${V1_ROOT}/connector-bindings/${encodeURIComponent(id)}:disable`, destructive: true }] }} /> }, { id: 'worker', label: 'Workers', content: <Workspace spec={{ eyebrow: 'Integration', title: 'Connector Workers', description: 'Public registration lookup. Internal worker execution protocol is never exposed to the browser.', detail: (id) => `${V1_ROOT}/connector-workers/${encodeURIComponent(id)}`, actions: [{ label: 'Disable', path: (id) => `${V1_ROOT}/connector-workers/${encodeURIComponent(id)}:disable`, destructive: true }] }} /> }, { id: 'ops', label: 'Operations', content: <IntegrationOperationsPage /> }]} />;
}

function adminSpec(kind: 'grant' | 'delegation' | 'elevation' | 'break-glass'): Spec {
  if (kind === 'grant') return { eyebrow: 'Administration', title: 'Administrative Grants', description: 'Direct scoped authority.', list: list(`${V1_ROOT}/administrative-grants`), detail: (id) => `${V1_ROOT}/administrative-grants/${encodeURIComponent(id)}`, columns: ['beneficiaryIdentityId', 'roleId', 'state', 'validUntil'], create: { path: `${V1_ROOT}/administrative-grants`, fields: [{ name: 'beneficiaryIdentityId', label: 'Beneficiary Identity ID', required: true }, { name: 'roleId', label: 'Admin Role ID', required: true }, ...scopeFields, { name: 'validUntil', label: 'Valid until', type: 'datetime-local' }, { name: 'grantable', label: 'Grantable', type: 'checkbox' }, { name: 'delegable', label: 'Delegable', type: 'checkbox' }, { name: 'authorityBasisGrantId', label: 'Authority basis Grant ID', required: true }], body: (values) => ({ beneficiaryIdentityId: values.beneficiaryIdentityId, roleId: values.roleId, scope: scope(values), validFrom: null, validUntil: iso(values.validUntil), grantable: Boolean(values.grantable), delegable: Boolean(values.delegable), authorityBasisGrantId: values.authorityBasisGrantId }) }, actions: [{ label: 'Revoke', path: (id) => `${V1_ROOT}/administrative-grants/${encodeURIComponent(id)}:revoke`, destructive: true }] };
  if (kind === 'delegation') return { eyebrow: 'Administration', title: 'Administrative Delegations', description: 'Single-hop delegated authority.', list: list(`${V1_ROOT}/administrative-delegations`), detail: (id) => `${V1_ROOT}/administrative-delegations/${encodeURIComponent(id)}`, columns: ['delegateIdentityId', 'sourceGrantId', 'state', 'validUntil'], actions: [{ label: 'Revoke', path: (id) => `${V1_ROOT}/administrative-delegations/${encodeURIComponent(id)}:revoke`, destructive: true }] };
  if (kind === 'elevation') return { eyebrow: 'Administration', title: 'Administrative Elevations', description: 'Finite JIT authority; Governance approval is evidence, Administration owns authority.', list: list(`${V1_ROOT}/administrative-elevations`), detail: (id) => `${V1_ROOT}/administrative-elevations/${encodeURIComponent(id)}`, columns: ['beneficiaryIdentityId', 'roleId', 'state', 'validUntil'], actions: [{ label: 'Request approval', path: (id) => `${V1_ROOT}/administrative-elevations/${encodeURIComponent(id)}:request-approval` }, { label: 'Apply approval', path: (id) => `${V1_ROOT}/administrative-elevations/${encodeURIComponent(id)}:apply` }, { label: 'Cancel', path: (id) => `${V1_ROOT}/administrative-elevations/${encodeURIComponent(id)}:cancel`, destructive: true }, { label: 'Revoke', path: (id) => `${V1_ROOT}/administrative-elevations/${encodeURIComponent(id)}:revoke`, destructive: true }] };
  return { eyebrow: 'Administration · emergency', title: 'Break-glass Operations', description: 'Emergency authority remains distinct, finite and post-use reviewed.', list: list(`${V1_ROOT}/administrative-break-glass-operations`), detail: (id) => `${V1_ROOT}/administrative-break-glass-operations/${encodeURIComponent(id)}`, columns: ['beneficiaryIdentityId', 'roleId', 'state', 'validUntil'], actions: [{ label: 'Revoke', path: (id) => `${V1_ROOT}/administrative-break-glass-operations/${encodeURIComponent(id)}:revoke`, destructive: true }, { label: 'Complete review', path: (id) => `${V1_ROOT}/administrative-break-glass-operations/${encodeURIComponent(id)}:complete-review`, fields: [{ name: 'outcome', label: 'Outcome', type: 'select', options: ['APPROVED_USE', 'POLICY_CONCERN', 'INCIDENT_FOLLOW_UP_REQUIRED'], required: true }, { name: 'summary', label: 'Summary', type: 'textarea', required: true }], body: (values) => ({ outcome: values.outcome, summary: values.summary }) }] };
}

export function PrincipalsPage() { return <Workspace spec={principalSpec} />; }
export function CatalogPage() { return <WorkspaceTabs tabs={[{ id: 'apps', label: 'Applications', content: <Workspace spec={applicationSpec} /> }, { id: 'targets', label: 'Targets', content: <ScopedCatalog kind="targets" /> }, { id: 'entitlements', label: 'Entitlements', content: <ScopedCatalog kind="entitlements" /> }, { id: 'roles', label: 'Roles', content: <Workspace spec={roleSpec} /> }, { id: 'versions', label: 'Role versions', content: <ScopedCatalog kind="versions" /> }]} />; }
export function AccessPage() { return <WorkspaceTabs tabs={[{ id: 'intent', label: 'Assignments', content: <Workspace spec={assignmentSpec} /> }, { id: 'effective', label: 'Effective access', content: <Workspace spec={effectiveSpec} /> }, { id: 'policy', label: 'Lifecycle policy', content: <Workspace spec={{ eyebrow: 'Access · policy', title: 'Lifecycle Access Policy', description: 'Typed policy read/activation is available at the accepted semantic endpoint; generic expression editing is intentionally not provided.', detail: () => `${V1_ROOT}/lifecycle-access-policy` }} /> }]} />; }
export function RequestsPage() { return <WorkspaceTabs tabs={[{ id: 'request', label: 'Access request', content: <AccessRequestPage /> }, { id: 'approval', label: 'Approvals', content: <Workspace spec={approvalSpec} /> }]} />; }
export function ReviewsPage() { return <WorkspaceTabs tabs={[{ id: 'campaign', label: 'Campaigns', content: <Workspace spec={campaignSpec} /> }, { id: 'inbox', label: 'Review inbox', content: <Workspace spec={reviewSpec} /> }]} />; }
export function PoliciesPage() { return <WorkspaceTabs tabs={[{ id: 'governance', label: 'ACCESS_REQUEST', content: <Workspace spec={{ eyebrow: 'Governance · policy', title: 'ACCESS_REQUEST Policy Versions', description: 'Lookup and explicit lifecycle transitions.', detail: (id) => `${V1_ROOT}/governance/policies/access-request/versions/${encodeURIComponent(id)}`, actions: [{ label: 'READY', path: (id) => `${V1_ROOT}/governance/policies/access-request/versions/${encodeURIComponent(id)}:ready` }, { label: 'Activate', path: (id) => `${V1_ROOT}/governance/policies/access-request/versions/${encodeURIComponent(id)}:activate` }, { label: 'Cancel', path: (id) => `${V1_ROOT}/governance/policies/access-request/versions/${encodeURIComponent(id)}:cancel`, destructive: true }] }} /> }, { id: 'exceptions', label: 'Exceptions', content: <Workspace spec={{ eyebrow: 'Governance', title: 'Governance Exceptions', description: 'Scoped/time-bound exception lookup and revocation.', detail: (id) => `${V1_ROOT}/governance/exceptions/${encodeURIComponent(id)}`, actions: [{ label: 'Revoke', path: (id) => `${V1_ROOT}/governance/exceptions/${encodeURIComponent(id)}:revoke`, destructive: true }] }} /> }, { id: 'source', label: 'Source policies', content: <Workspace spec={{ eyebrow: 'Identity · source policy', title: 'Source Policy Lookup', description: 'SourceSystem policy endpoints require a SourceSystem identifier. Use the exact resource path via the accepted public API; SourceSystem administration itself is not a public console surface.', detail: (id) => `${V1_ROOT}/source-systems/${encodeURIComponent(id)}/correlation-policy` }} /> }]} />; }
export function CredentialsPage() { return <CredentialPageInner />; }
export function IntegrationsPage() { return <IntegrationPageInner />; }
export function AdministrationPage() { return <WorkspaceTabs tabs={[{ id: 'roles', label: 'Roles', content: <Workspace spec={adminRoleSpec} /> }, { id: 'grants', label: 'Grants', content: <Workspace spec={adminSpec('grant')} /> }, { id: 'delegations', label: 'Delegations', content: <Workspace spec={adminSpec('delegation')} /> }, { id: 'elevations', label: 'Elevations', content: <Workspace spec={adminSpec('elevation')} /> }, { id: 'breakglass', label: 'Break-glass', content: <Workspace spec={adminSpec('break-glass')} /> }, { id: 'authority', label: 'My authority', content: <CurrentAdministrativeAuthorityPage /> }]} />; }
export function AuditPage() { return <WorkspaceTabs tabs={[{ id: 'records', label: 'Records', content: <Workspace spec={{ eyebrow: 'Audit', title: 'Audit Records', description: 'Append-only semantic business/security evidence, not generic logging.', list: list(`${V1_ROOT}/audit-records`), detail: (id) => `${V1_ROOT}/audit-records/${encodeURIComponent(id)}`, columns: ['occurredAt', 'actionType', 'resourceType', 'outcome', 'actorId'] }} /> }, { id: 'exports', label: 'Exports', content: <Workspace spec={{ eyebrow: 'Audit', title: 'Audit Exports', description: 'Durable snapshot-frozen exports with re-authorization at download.', detail: (id) => `${V1_ROOT}/audit-exports/${encodeURIComponent(id)}`, actions: [{ label: 'Download NDJSON', path: (id) => `${V1_ROOT}/audit-exports/${encodeURIComponent(id)}:download`, download: true, requireEtag: false, idempotent: false }] }} /> }, { id: 'evidence', label: 'Evidence', content: <Workspace spec={{ eyebrow: 'Audit', title: 'Evidence Snapshots', description: 'Immutable evidence snapshots.', list: list(`${V1_ROOT}/evidence-snapshots`), detail: (id) => `${V1_ROOT}/evidence-snapshots/${encodeURIComponent(id)}`, columns: ['snapshotType', 'subjectResourceType', 'subjectResourceId', 'createdAt'] }} /> }, { id: 'holds', label: 'Legal hold', content: <Workspace spec={{ eyebrow: 'Audit · evidence lifecycle', title: 'Legal Holds', description: 'Governed evidence retention hold.', detail: (id) => `${V1_ROOT}/audit-evidence-lifecycle/legal-holds/${encodeURIComponent(id)}`, actions: [{ label: 'Release', path: (id) => `${V1_ROOT}/audit-evidence-lifecycle/legal-holds/${encodeURIComponent(id)}:release`, destructive: true, idempotent: false }] }} /> }, { id: 'purges', label: 'Purge', content: <Workspace spec={{ eyebrow: 'Audit · destructive evidence', title: 'Purge Operations', description: 'Dual-control destructive evidence operation.', detail: (id) => `${V1_ROOT}/audit-evidence-lifecycle/purges/${encodeURIComponent(id)}`, actions: [{ label: 'Approve purge', path: (id) => `${V1_ROOT}/audit-evidence-lifecycle/purges/${encodeURIComponent(id)}:approve`, destructive: true, idempotent: false }] }} /> }]} />; }
