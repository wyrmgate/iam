import { V1_ROOT } from '../api';
import { type Spec, Workspace, WorkspaceTabs } from '../SemanticWorkspace';
import { iso, list, nullable } from './workspaceSpecHelpers';

const campaignSpec: Spec = {
  eyebrow: 'Governance · review campaign',
  title: 'Review Campaigns',
  description: 'Campaign lifecycle and generation state. COMPLETED means every generated item has a decision; remediation and provider fulfillment remain separate.',
  list: list(`${V1_ROOT}/governance/review-campaigns`),
  detail: (id) => `${V1_ROOT}/governance/review-campaigns/${encodeURIComponent(id)}`,
  columns: ['subjectIdentityId', 'reviewerIdentityId', 'state', 'generatedItemCount'],
  create: {
    path: `${V1_ROOT}/governance/review-campaigns`,
    fields: [
      { name: 'subjectIdentityId', label: 'Subject Identity ID', required: true, help: 'The governed Identity whose authoritative AccessAssignments are reviewed.' },
      { name: 'reviewerIdentityId', label: 'Reviewer Identity ID', required: true, help: 'The explicit governed Identity assigned to decide generated ReviewItems.' },
      { name: 'snapshotAt', label: 'Snapshot cutoff', type: 'datetime-local', required: true, help: 'Fixed cutoff for bounded review generation; the resulting snapshot is evidence, not mutation authority.' },
    ],
    body: (values) => ({
      subjectIdentityId: values.subjectIdentityId,
      reviewerIdentityId: values.reviewerIdentityId,
      snapshotAt: iso(values.snapshotAt),
    }),
  },
  actions: [{ label: 'Start generation', path: (id) => `${V1_ROOT}/governance/review-campaigns/${encodeURIComponent(id)}/start` }],
};

const reviewSpec: Spec = {
  eyebrow: 'Governance · review decision',
  title: 'Review Inbox',
  description: 'Assigned ReviewItems with immutable snapshot context. KEEP records evidence only; REVOKE creates durable remediation that re-reads current Access state.',
  list: list(`${V1_ROOT}/governance/review-inbox`),
  detail: (id) => `${V1_ROOT}/governance/review-items/${encodeURIComponent(id)}`,
  columns: ['identityId', 'targetKind', 'state', 'decision'],
  actions: [
    {
      label: 'KEEP — retain current access',
      path: (id) => `${V1_ROOT}/governance/review-items/${encodeURIComponent(id)}/keep`,
      fields: [{ name: 'reason', label: 'Decision reason', type: 'textarea', help: 'Optional rationale stored with immutable KEEP evidence. KEEP creates no Access mutation work.' }],
      body: (values) => ({ reason: nullable(values.reason) }),
    },
    {
      label: 'REVOKE — request access reduction',
      path: (id) => `${V1_ROOT}/governance/review-items/${encodeURIComponent(id)}/revoke`,
      destructive: true,
      fields: [{ name: 'reason', label: 'Decision reason', type: 'textarea', help: 'Optional rationale stored with immutable REVOKE evidence. Remediation uses current Access semantics rather than rewriting the historical snapshot.' }],
      body: (values) => ({ reason: nullable(values.reason) }),
    },
  ],
};

const remediationSpec: Spec = {
  eyebrow: 'Governance · review remediation',
  title: 'Review Remediation Status',
  description: 'Lookup durable remediation process state created by a REVOKE decision. APPLIED means Access accepted the authoritative reduction; it does not mean provider removal is complete.',
  detail: (id) => `${V1_ROOT}/governance/review-remediations/${encodeURIComponent(id)}`,
};

export function ReviewsWorkspace() {
  return <WorkspaceTabs tabs={[
    { id: 'campaign', label: 'Campaign state', content: <Workspace spec={campaignSpec} /> },
    { id: 'inbox', label: 'Review decisions', content: <Workspace spec={reviewSpec} /> },
    { id: 'remediation', label: 'Remediation status', content: <Workspace spec={remediationSpec} /> },
  ]} />;
}

export function PoliciesWorkspace() {
  return <WorkspaceTabs tabs={[
    {
      id: 'governance',
      label: 'ACCESS_REQUEST policy',
      content: <Workspace spec={{
        eyebrow: 'Governance · typed policy version',
        title: 'ACCESS_REQUEST Policy Versions',
        description: 'Lookup one typed immutable-content PolicyVersion and use only its explicit lifecycle operations: DRAFT → READY → ACTIVE → SUPERSEDED, with cancellation before activation. Activated/superseded content is not edited in place.',
        detail: (id) => `${V1_ROOT}/governance/policies/access-request/versions/${encodeURIComponent(id)}`,
        actions: [
          { label: 'Mark DRAFT as READY', path: (id) => `${V1_ROOT}/governance/policies/access-request/versions/${encodeURIComponent(id)}:ready` },
          { label: 'Activate READY version', path: (id) => `${V1_ROOT}/governance/policies/access-request/versions/${encodeURIComponent(id)}:activate` },
          { label: 'Cancel pre-activation version', path: (id) => `${V1_ROOT}/governance/policies/access-request/versions/${encodeURIComponent(id)}:cancel`, destructive: true },
        ],
      }} />,
    },
    {
      id: 'exceptions',
      label: 'Governance exceptions',
      content: <Workspace spec={{
        eyebrow: 'Governance · scoped exception',
        title: 'Governance Exceptions',
        description: 'Lookup an explicit IDENTITY_SOD_RULE exception by ID. Exception authority is scoped to one Identity and exact SoDRule, bounded by validity, and never removes the underlying SoD/risk evidence.',
        detail: (id) => `${V1_ROOT}/governance/exceptions/${encodeURIComponent(id)}`,
        actions: [{ label: 'Revoke exception authority', path: (id) => `${V1_ROOT}/governance/exceptions/${encodeURIComponent(id)}:revoke`, destructive: true }],
      }} />,
    },
    {
      id: 'source',
      label: 'Identity source policy',
      content: <Workspace spec={{
        eyebrow: 'Identity · source policy',
        title: 'Source Policy Lookup',
        description: 'Governance consumes the existing accepted Identity source-policy endpoint unchanged. Enter a SourceSystem ID to inspect its correlation policy; SourceSystem administration is not introduced here.',
        detail: (id) => `${V1_ROOT}/source-systems/${encodeURIComponent(id)}/correlation-policy`,
      }} />,
    },
  ]} />;
}
