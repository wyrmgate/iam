import { V1_ROOT } from '../api';
import { type Spec, Workspace, WorkspaceTabs } from '../SemanticWorkspace';
import { iso, list, nullable } from './workspaceSpecHelpers';

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

function AccessRequestPage() {
  return <Workspace spec={{ eyebrow: 'Governance', title: 'Access Requests', description: 'Create/read/submit typed access requests.', detail: (id) => `${V1_ROOT}/governance/access-requests/${encodeURIComponent(id)}`, create: { path: `${V1_ROOT}/governance/access-requests`, fields: [{ name: 'beneficiaryIdentityId', label: 'Beneficiary Identity ID', required: true }, { name: 'targetKind', label: 'Target kind', type: 'select', options: ['ROLE', 'ENTITLEMENT'], required: true }, { name: 'targetId', label: 'Target ID', required: true }, { name: 'principalConstraintKind', label: 'Principal constraint', type: 'select', options: ['ANY', 'SPECIFIC'], required: true }, { name: 'specificPrincipalId', label: 'Specific Principal ID' }], body: (values) => ({ beneficiaryIdentityId: values.beneficiaryIdentityId, items: [{ targetKind: values.targetKind, targetId: values.targetId, principalConstraintKind: values.principalConstraintKind, specificPrincipalId: nullable(values.specificPrincipalId), validFrom: null, validUntil: null }] }) }, actions: [{ label: 'Submit', path: (id) => `${V1_ROOT}/governance/access-requests/${encodeURIComponent(id)}/submit` }] }} />;
}

export function RequestsWorkspace() {
  return <WorkspaceTabs tabs={[
    { id: 'request', label: 'Access request', content: <AccessRequestPage /> },
    { id: 'approval', label: 'Approvals', content: <Workspace spec={approvalSpec} /> },
  ]} />;
}

export function ReviewsWorkspace() {
  return <WorkspaceTabs tabs={[
    { id: 'campaign', label: 'Campaigns', content: <Workspace spec={campaignSpec} /> },
    { id: 'inbox', label: 'Review inbox', content: <Workspace spec={reviewSpec} /> },
  ]} />;
}

export function PoliciesWorkspace() {
  return <WorkspaceTabs tabs={[
    { id: 'governance', label: 'ACCESS_REQUEST', content: <Workspace spec={{ eyebrow: 'Governance · policy', title: 'ACCESS_REQUEST Policy Versions', description: 'Lookup and explicit lifecycle transitions.', detail: (id) => `${V1_ROOT}/governance/policies/access-request/versions/${encodeURIComponent(id)}`, actions: [{ label: 'READY', path: (id) => `${V1_ROOT}/governance/policies/access-request/versions/${encodeURIComponent(id)}:ready` }, { label: 'Activate', path: (id) => `${V1_ROOT}/governance/policies/access-request/versions/${encodeURIComponent(id)}:activate` }, { label: 'Cancel', path: (id) => `${V1_ROOT}/governance/policies/access-request/versions/${encodeURIComponent(id)}:cancel`, destructive: true }] }} /> },
    { id: 'exceptions', label: 'Exceptions', content: <Workspace spec={{ eyebrow: 'Governance', title: 'Governance Exceptions', description: 'Scoped/time-bound exception lookup and revocation.', detail: (id) => `${V1_ROOT}/governance/exceptions/${encodeURIComponent(id)}`, actions: [{ label: 'Revoke', path: (id) => `${V1_ROOT}/governance/exceptions/${encodeURIComponent(id)}:revoke`, destructive: true }] }} /> },
    { id: 'source', label: 'Source policies', content: <Workspace spec={{ eyebrow: 'Identity · source policy', title: 'Source Policy Lookup', description: 'SourceSystem policy endpoints require a SourceSystem identifier. Use the exact resource path via the accepted public API; SourceSystem administration itself is not a public console surface.', detail: (id) => `${V1_ROOT}/source-systems/${encodeURIComponent(id)}/correlation-policy` }} /> },
  ]} />;
}
