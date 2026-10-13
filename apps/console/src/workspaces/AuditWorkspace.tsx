import { V1_ROOT } from '../api';
import { Workspace, WorkspaceTabs } from '../SemanticWorkspace';
import { list } from './workspaceSpecHelpers';

export function AuditWorkspace() {
  return <WorkspaceTabs tabs={[
    { id: 'records', label: 'Records', content: <Workspace spec={{ eyebrow: 'Audit', title: 'Audit Records', description: 'Append-only semantic business/security evidence, not generic logging.', list: list(`${V1_ROOT}/audit-records`), detail: (id) => `${V1_ROOT}/audit-records/${encodeURIComponent(id)}`, columns: ['occurredAt', 'actionType', 'resourceType', 'outcome', 'actorId'] }} /> },
    { id: 'exports', label: 'Exports', content: <Workspace spec={{ eyebrow: 'Audit', title: 'Audit Exports', description: 'Durable snapshot-frozen exports with re-authorization at download.', detail: (id) => `${V1_ROOT}/audit-exports/${encodeURIComponent(id)}`, actions: [{ label: 'Download NDJSON', path: (id) => `${V1_ROOT}/audit-exports/${encodeURIComponent(id)}:download`, download: true, requireEtag: false, idempotent: false }] }} /> },
    { id: 'evidence', label: 'Evidence', content: <Workspace spec={{ eyebrow: 'Audit', title: 'Evidence Snapshots', description: 'Immutable evidence snapshots.', list: list(`${V1_ROOT}/evidence-snapshots`), detail: (id) => `${V1_ROOT}/evidence-snapshots/${encodeURIComponent(id)}`, columns: ['snapshotType', 'subjectResourceType', 'subjectResourceId', 'createdAt'] }} /> },
    { id: 'holds', label: 'Legal hold', content: <Workspace spec={{ eyebrow: 'Audit · evidence lifecycle', title: 'Legal Holds', description: 'Governed evidence retention hold.', detail: (id) => `${V1_ROOT}/audit-evidence-lifecycle/legal-holds/${encodeURIComponent(id)}`, actions: [{ label: 'Release', path: (id) => `${V1_ROOT}/audit-evidence-lifecycle/legal-holds/${encodeURIComponent(id)}:release`, destructive: true, idempotent: false }] }} /> },
    { id: 'purges', label: 'Purge', content: <Workspace spec={{ eyebrow: 'Audit · destructive evidence', title: 'Purge Operations', description: 'Dual-control destructive evidence operation.', detail: (id) => `${V1_ROOT}/audit-evidence-lifecycle/purges/${encodeURIComponent(id)}`, actions: [{ label: 'Approve purge', path: (id) => `${V1_ROOT}/audit-evidence-lifecycle/purges/${encodeURIComponent(id)}:approve`, destructive: true, idempotent: false }] }} /> },
  ]} />;
}
